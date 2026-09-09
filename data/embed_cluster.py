"""임베딩 + 군집 (ANT-DATA-10 1단계). GPU 서버용이지만 --device cpu 로 로컬 표본 검증도 된다.

  # 로컬 표본 검증 (2만 건, CPU 5~10분)
  python embed_cluster.py --input cache/embed_input_sample.parquet --device cpu --k 200 --out cache/emb_sample

  # GPU 서버 (Device 3 지정 필수 — 공용 서버)
  CUDA_VISIBLE_DEVICES=3 OMP_NUM_THREADS=8 python embed_cluster.py --input "cache/embed_input_20*.parquet" --device cuda --k 10000 --out cache/emb_full

산출 (out 폴더):
  embeddings.npy        (N, dim) float16 — 재군집·분류기 학습에 재사용. 793만 건이면 약 12GB, 서버에 둔다
  keys.parquet          행 순서 = embeddings 행 순서. link, yr, published_date
  clusters.parquet      link → cluster_id, dist_to_center            ← 로컬로 가져갈 것 (수십 MB)
  cluster_summary.parquet  cluster_id → size, 중심 근처 대표 제목 10개, 경계 제목 5개, 연도 분포   ← LLM 판정 입력 (수 MB)

설계 근거: .claude/docs-personal/impl/ANT-DATA-09/plan.md · 이슈 S15P21A507-219
"""
import argparse
import glob
import json
import os
import sys
import time
from pathlib import Path

import numpy as np

sys.stdout.reconfigure(encoding="utf-8")

ap = argparse.ArgumentParser()
ap.add_argument("--input", required=True, help="parquet 경로 또는 글롭 (link, title, text, yr, published_date 열)")
ap.add_argument("--out", required=True, help="산출 폴더")
ap.add_argument("--model", default="intfloat/multilingual-e5-base", help="sentence-transformers 모델. 대안: nlpai-lab/KoE5, intfloat/multilingual-e5-small")
ap.add_argument("--device", default="cuda", choices=["cuda", "cpu"])
ap.add_argument("--batch", type=int, default=256, help="GPU 24GB 면 512, 8GB 면 128, CPU 는 64")
ap.add_argument("--max-tokens", type=int, default=128, help="제목+리드 300자는 128토큰이면 거의 다 들어간다. 길면 잘린다")
ap.add_argument("--k", type=int, default=10000, help="k-means 군집 수. 793만이면 1만(군집당 ~800), 표본 2만이면 200")
ap.add_argument("--skip-embed", action="store_true", help="embeddings.npy 가 이미 있으면 군집만 다시")
args = ap.parse_args()

out = Path(args.out)
out.mkdir(parents=True, exist_ok=True)
t0 = time.time()


def log(msg):
    print(f"[{time.time()-t0:7.0f}s] {msg}", flush=True)


# ── 1. 입력 ─────────────────────────────────────────────────────────────────
import duckdb

files = sorted(glob.glob(args.input)) if any(c in args.input for c in "*?[") else [args.input]
assert files, f"입력 없음: {args.input}"
con = duckdb.connect()
keys = con.sql(
    f"SELECT link, yr, published_date, title, text FROM read_parquet({json.dumps(files)}) ORDER BY yr, published_date, link"
).arrow()
n = keys.num_rows
log(f"입력 {n:,} 행 · 파일 {len(files)}개")
texts = keys.column("text").to_pylist()

# ── 2. 임베딩 ───────────────────────────────────────────────────────────────
emb_path = out / "embeddings.npy"
if args.skip_embed and emb_path.exists():
    emb = np.load(emb_path, mmap_mode="r")
    log(f"기존 임베딩 재사용 {emb.shape}")
else:
    import torch
    from sentence_transformers import SentenceTransformer

    if args.device == "cuda" and not torch.cuda.is_available():
        sys.exit("CUDA 를 찾지 못했다. CUDA_VISIBLE_DEVICES 와 torch 설치(cu 버전)를 확인. CPU 로 돌리려면 --device cpu")
    if args.device == "cuda":
        log(f"GPU: {torch.cuda.get_device_name(0)} · 보이는 장치 {torch.cuda.device_count()}개 (CUDA_VISIBLE_DEVICES={os.environ.get('CUDA_VISIBLE_DEVICES')})")
    model = SentenceTransformer(args.model, device=args.device)
    model.max_seq_length = args.max_tokens
    # e5 계열은 문서 쪽에 'passage: ' 접두를 붙이라고 권고한다. 다른 모델이면 무해하다.
    prefix = "passage: " if "e5" in args.model.lower() else ""
    dim = model.get_sentence_embedding_dimension()
    emb = np.lib.format.open_memmap(emb_path, mode="w+", dtype=np.float16, shape=(n, dim))
    chunk = 50_000  # 진행 로그·중간 저장 단위
    for s in range(0, n, chunk):
        e = model.encode(
            [prefix + t for t in texts[s : s + chunk]],
            batch_size=args.batch,
            normalize_embeddings=True,  # 코사인 = 내적. k-means 도 L2 로 바로 된다
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        emb[s : s + len(e)] = e.astype(np.float16)
        log(f"임베딩 {min(s+chunk, n):,}/{n:,}")
    emb.flush()
    del model
    if args.device == "cuda":
        torch.cuda.empty_cache()

# ── 3. 군집 (k-means) ───────────────────────────────────────────────────────
X = np.asarray(emb, dtype=np.float32)
k = min(args.k, max(2, n // 20))  # 표본이 작으면 k 를 줄인다 (군집당 최소 20건)
try:
    import faiss

    d = X.shape[1]
    km = faiss.Kmeans(d, k, niter=20, seed=42, gpu=(args.device == "cuda"), spherical=True, verbose=False)
    km.train(X)
    dist, lab = km.index.search(X, 1)
    labels, dists = lab[:, 0], dist[:, 0]
    log(f"faiss k-means k={k} 완료")
except ImportError:
    from sklearn.cluster import MiniBatchKMeans

    km = MiniBatchKMeans(n_clusters=k, batch_size=4096, n_init=3, random_state=42).fit(X)
    labels = km.labels_
    dists = np.linalg.norm(X - km.cluster_centers_[labels], axis=1)
    log(f"sklearn MiniBatchKMeans k={k} 완료 (faiss 없음 — 서버에서는 faiss-gpu 권장)")

# ── 4. 산출 ─────────────────────────────────────────────────────────────────
import pyarrow as pa
import pyarrow.parquet as pq

pq.write_table(keys.select(["link", "yr", "published_date"]), out / "keys.parquet", compression="zstd")
clusters = pa.table(
    {"link": keys.column("link"), "cluster_id": pa.array(labels.astype(np.int32)), "dist_to_center": pa.array(dists.astype(np.float32))}
)
pq.write_table(clusters, out / "clusters.parquet", compression="zstd")

# 군집 요약: 크기 · 중심 근처 대표 제목 10 · 경계 제목 5 · 연도 분포 — LLM 이 읽을 입력
con.register("c", clusters)
con.register("k", keys.select(["link", "yr", "title"]))
summary = con.sql(
    """
    WITH j AS (
        SELECT c.cluster_id, c.dist_to_center, k.yr, k.title,
               row_number() OVER (PARTITION BY c.cluster_id ORDER BY c.dist_to_center)      AS r_center,
               row_number() OVER (PARTITION BY c.cluster_id ORDER BY c.dist_to_center DESC) AS r_edge
        FROM c JOIN k USING (link)
    )
    SELECT cluster_id,
           count(*)                                                        AS size,
           round(avg(dist_to_center), 4)                                   AS avg_dist,
           list(title ORDER BY r_center) FILTER (WHERE r_center <= 10)     AS center_titles,
           list(title ORDER BY r_edge)   FILTER (WHERE r_edge <= 5)        AS edge_titles,
           map_from_entries(list(struct_pack(k := yr::VARCHAR, v := 1)))   AS _dummy
    FROM j GROUP BY 1 ORDER BY size DESC
    """
).arrow()
# 연도 분포는 별도 집계 (map 집계가 번거로워 문자열로)
yr_dist = con.sql(
    "SELECT cluster_id, string_agg(yr || ':' || n, ' ' ORDER BY yr) AS year_dist FROM (SELECT c.cluster_id, k.yr, count(*) n FROM c JOIN k USING (link) GROUP BY 1,2) GROUP BY 1"
).arrow()
summary = summary.drop_columns(["_dummy"]).join(yr_dist, keys="cluster_id")
pq.write_table(summary, out / "cluster_summary.parquet", compression="zstd")

log(f"완료 → {out}/  (embeddings {emb_path.stat().st_size/1e9:.2f} GB · clusters {os.path.getsize(out/'clusters.parquet')/1e6:.1f} MB · summary {os.path.getsize(out/'cluster_summary.parquet')/1e6:.1f} MB)")
print("\n군집 크기 상위 10 · 대표 제목 3개")
for row in summary.slice(0, 10).to_pylist():
    print(f"  #{row['cluster_id']:>5} size={row['size']:>7,}  {row['center_titles'][:3]}")
