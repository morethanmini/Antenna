"""이미 판정한 기사·종목 쌍을 새 후보에 물려준다 (GPU 시간 아끼기).

시즌 구간을 바꾸면 후보 id 가 전부 새로 매겨지지만, 판정 결과는 기사 링크와 종목코드에만 달려
있다(지시문이 같은 한). 옛 결과를 (링크, 종목코드)로 찾아 새 id 로 옮겨 두면, gpu_judge.py 가
그 id 를 건너뛴다.

    python3 reuse_judgments.py out/candidates-old.jsonl out/judged-old.jsonl out/candidates.jsonl out/judged.jsonl
"""
import json, sys

old_cand, old_judged, new_cand, out = sys.argv[1:5]
key = {}
for line in open(old_cand, encoding="utf-8"):
    c = json.loads(line)
    key[c["id"]] = (c["link"], c["code"])
verdict = {}
for line in open(old_judged, encoding="utf-8"):
    r = json.loads(line)
    if r["id"] in key and r["gpu"] is not None:
        verdict[key[r["id"]]] = r["gpu"]

reused = 0
with open(out, "w", encoding="utf-8") as f:
    for line in open(new_cand, encoding="utf-8"):
        c = json.loads(line)
        hit = verdict.get((c["link"], c["code"]))
        if hit is None:
            continue
        f.write(json.dumps({"id": c["id"], "gpu": hit, "raw": "재활용"}, ensure_ascii=False) + "\n")
        reused += 1
total = sum(1 for _ in open(new_cand, encoding="utf-8"))
print(f"새 후보 {total:,} · 옛 판정 재활용 {reused:,} · GPU 로 보낼 것 {total - reused:,}")
