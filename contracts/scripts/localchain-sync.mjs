import { JsonRpcProvider, Contract } from 'ethers';
import pg from 'pg';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

/**
 * 로컬 체인과 로컬 DB 를 맞춘다 (ANT-CHAIN-15). chain 컨테이너가 노드를 띄운 직후 부른다.
 *
 *   npm run localchain:sync      # 노드가 떠 있을 때 손으로 다시 돌릴 때(컨테이너 안이든 호스트든)
 *
 * Hardhat 노드는 체인을 메모리에만 든다 — 컨테이너가 다시 뜨면 체인이 새로 생긴다. 그런데 로컬 DB 는 남아 있어서 DB 의 체인 사본
 * (이벤트·원장·작업)이 "없는 체인" 을 설명하게 된다. 인덱서는 옛 블록 해시로 새 체인을 검사하다 멈추고, 가입 보너스는 "이미 받음" 으로
 * 보여 다시 안 나간다. 그래서 새 체인이면:
 *
 *   ① 컨트랙트 배포 — 새 노드에서는 주소가 늘 같다(deploy.js 의 배포 순서, ANT-CHAIN-14)
 *   ② DB 의 체인 사본 비우기 — chain_events · token_ledger · operations. 회원·연동·예측·앵커 배치·시세는 그대로
 *      → 연동 회원의 가입 보너스는 서버의 재시도 배치(ANT-TOKEN-07)가 새 체인에 다시 준다
 *   ③ 확정된 앵커 다시 보내기 — 같은 루트, 같은 리프 순서(prediction id 오름차순). tx·블록 참조는 서버 인덱서가 새 값으로 고친다
 *
 * DB 를 체인에 "그대로 다시 쓰지" 않는 이유: 원장·이벤트는 append-only 이고 새 체인에서는 tx 해시·블록이 달라진다. 옛 행을 두고
 * 다시 mint 하면 인덱서가 멈추거나 새 mint 를 한 번 더 적는다. 로컬이라 잔액 이력은 버리고 필요한 상태만 다시 만든다.
 *
 * <b>안전장치 두 개.</b>
 *   - 체인 id 가 31337 이 아니면 멈춘다 — SSAFY 체인에 잘못 붙어 배포하지 않게.
 *   - backend/.env 가 이 로컬 체인을 볼 때만 DB 를 만진다. 운영 체인 세트로 로컬을 쓰는 사람의 DB 에는 운영 체인 사본이 있다 — 지우면 안 된다.
 */

const here = path.dirname(fileURLToPath(import.meta.url));
const CONTRACTS_DIR = path.resolve(here, '..');

const RPC_URL = process.env.LOCALCHAIN_RPC_URL || 'http://127.0.0.1:8545';
const LOCAL_CHAIN_ID = 31337n;
/** deploy.js 가 새 노드에서 만드는 주소 — contracts/deployments/README.md 표와 같다. 배포 순서를 바꾸면 여기도 바뀐다. */
const COMMIT_ANCHOR = '0x5FbDB2315678afecb367f032d93F642f64180aa3';
const PREDICT_TOKEN = '0xe7f1725E7734CE288F8367e1Bb143E90bb3F0512';
/** deploy.js: signer 1 = 릴레이어(ANCHOR_ROLE · OPERATOR_ROLE). 노드가 잠금 해제해 둔 계정이라 키가 필요 없다. */
const RELAYER_SIGNER_INDEX = 1;
const BACKEND_ENV_FILE = process.env.BACKEND_ENV_FILE || path.resolve(CONTRACTS_DIR, '..', 'backend', '.env');
const BACKEND_HEALTH = process.env.LOCALCHAIN_BACKEND_HEALTH || 'http://127.0.0.1:8080/actuator/health';

const ANCHOR_ABI = [
  'function anchor(bytes32 merkleRoot, bytes32[] commitHashes)',
  'function anchoredAt(bytes32 merkleRoot) view returns (uint256)',
];

const log = (...args) => console.log('[localchain]', ...args);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function rpc(method, params = []) {
  const res = await fetch(RPC_URL, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }),
  });
  const body = await res.json();
  if (body.error) {
    throw new Error(body.error.message);
  }
  return body.result;
}

/** 노드가 RPC 를 열 때까지 기다린다. ethers provider 는 그 뒤에 만든다 — 연결 실패마다 재시도 로그를 쏟아 내서다. */
async function waitForChain(seconds = 120) {
  for (let i = 0; i < seconds; i++) {
    try {
      return BigInt(await rpc('eth_chainId'));
    } catch {
      await sleep(1000);
    }
  }
  throw new Error(`${RPC_URL} 가 ${seconds}초 안에 열리지 않았다`);
}

function deploy() {
  log('새 체인 — 컨트랙트를 배포한다(deploy.js)');
  const r = spawnSync('npx', ['hardhat', 'run', 'scripts/deploy.js', '--network', 'localhost'], {
    cwd: CONTRACTS_DIR,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (r.status !== 0) {
    throw new Error(`deploy.js 실패 (exit ${r.status})`);
  }
}

/** KEY=VALUE 만 읽는다. 값을 로그에 찍지 않는다 — 운영 세트 사용자의 .env 에는 운영 키가 있다. */
function readEnvFile(file) {
  if (!fs.existsSync(file)) {
    return null;
  }
  const env = {};
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line.startsWith('#')) {
      continue;
    }
    const i = line.indexOf('=');
    if (i > 0) {
      env[line.slice(0, i).trim()] = line.slice(i + 1).trim();
    }
  }
  return env;
}

/** backend/.env 의 DB 에 붙는다. 컨테이너 안에서는 localhost 가 자기 자신이라 compose 가 준 호스트·포트로 바꿔 끼운다. */
function dbConfig(env) {
  const hostIsLoopback = ['localhost', '127.0.0.1', undefined, ''].includes(env.DB_HOST);
  return {
    host: hostIsLoopback && process.env.LOCALCHAIN_DB_HOST ? process.env.LOCALCHAIN_DB_HOST : env.DB_HOST || 'localhost',
    port: Number(hostIsLoopback && process.env.LOCALCHAIN_DB_PORT ? process.env.LOCALCHAIN_DB_PORT : env.DB_PORT || 5432),
    database: env.DB_NAME || 'antenna',
    user: env.DB_USER || 'antenna',
    password: env.DB_PASSWORD || 'antenna',
  };
}

async function connect(config, seconds = 60) {
  let last;
  for (let i = 0; i < seconds; i++) {
    const client = new pg.Client(config);
    try {
      await client.connect();
      return client;
    } catch (e) {
      last = e;
      await client.end().catch(() => {});
      await sleep(1000);
    }
  }
  throw new Error(`DB ${config.host}:${config.port}/${config.database} 에 ${seconds}초 안에 못 붙었다 — ${last?.message}`);
}

/** ② 체인 사본 비우기. token_ledger 는 chain_events 를 FK 로 가리켜 한 문장에 같이 비운다. TRUNCATE 는 append-only 행 트리거를 타지 않는다. */
async function wipeChainCopy(db) {
  const { rows } = await db.query(
    `select (select count(*) from chain_events)  as events,
            (select count(*) from token_ledger)  as ledger,
            (select count(*) from operations)    as operations`,
  );
  await db.query('TRUNCATE chain_events, token_ledger, operations');
  const c = rows[0];
  log(`DB 체인 사본 비움 — chain_events ${c.events} · token_ledger ${c.ledger} · operations ${c.operations} 행 (회원·연동·예측·앵커 배치는 그대로)`);
}

const hex = (h) => (h.startsWith('0x') ? h : `0x${h}`);

/** ③ 확정 앵커 재전송. 리프 순서는 서버 AnchorBatchService 와 같은 prediction id 오름차순 — 다르면 컨트랙트가 RootMismatch 로 거부한다. */
async function reanchorConfirmed(db, provider) {
  const { rows: batches } = await db.query(
    `select id, merkle_root from anchor_batches
      where status = 'CONFIRMED' and lower(contract_address) = lower($1)
      order by id`,
    [COMMIT_ANCHOR],
  );
  const { rows: others } = await db.query(
    `select count(*) as n from anchor_batches where status = 'CONFIRMED' and lower(contract_address) <> lower($1)`,
    [COMMIT_ANCHOR],
  );
  if (Number(others[0].n) > 0) {
    log(`다른 컨트랙트(옛 dev·운영)에 확정된 배치 ${others[0].n}개는 건너뛴다 — 이 체인과 무관하다`);
  }
  if (batches.length === 0) {
    log('다시 보낼 로컬 앵커 배치 없음');
    return;
  }

  const anchor = new Contract(COMMIT_ANCHOR, ANCHOR_ABI, await provider.getSigner(RELAYER_SIGNER_INDEX));
  let sent = 0;
  let failed = 0;
  for (const b of batches) {
    const { rows } = await db.query(
      'select commit_hash from prediction_commits where anchor_batch_id = $1 order by prediction_id',
      [b.id],
    );
    const hashes = rows.map((r) => hex(r.commit_hash));
    if (hashes.length === 0) {
      log(`배치 #${b.id} — 소속 커밋이 없다. 건너뜀`);
      continue;
    }
    if ((await anchor.anchoredAt(b.merkle_root)) > 0n) {
      continue;
    }
    try {
      const tx = await anchor.anchor(b.merkle_root, hashes);
      const receipt = await tx.wait();
      sent++;
      log(`배치 #${b.id} 다시 앵커 — 커밋 ${hashes.length}건 · block ${receipt.blockNumber} · tx ${tx.hash}`);
    } catch (e) {
      failed++;
      log(`배치 #${b.id} 앵커 실패 — ${e.shortMessage || e.message}`);
    }
  }
  log(`확정 앵커 재전송 ${sent}건${failed ? ` · 실패 ${failed}건` : ''}. tx·블록 참조는 백엔드 인덱서가 새 체인 값으로 고친다`);
}

/** 백엔드가 떠 있으면 크게 알린다. TokenIndexer 가 옛 체인 높이를 메모리에 들고 있어 새 체인 이벤트를 못 읽는다. */
async function warnIfBackendRunning() {
  try {
    const res = await fetch(BACKEND_HEALTH, { signal: AbortSignal.timeout(2000) });
    if (res.ok) {
      log('⚠️  백엔드가 떠 있다 — 재시작해라. 인덱서가 옛 체인의 블록 높이를 메모리에 들고 있어 새 체인 이벤트를 못 읽는다');
    }
  } catch {
    // 안 떠 있음 — 정상
  }
}

async function main() {
  const chainId = await waitForChain();
  if (chainId !== LOCAL_CHAIN_ID) {
    throw new Error(`chainId ${chainId} — 로컬 체인(31337)이 아니다. 멈춘다`);
  }
  const provider = new JsonRpcProvider(RPC_URL, Number(LOCAL_CHAIN_ID), { staticNetwork: true });

  if ((await provider.getCode(COMMIT_ANCHOR)) !== '0x') {
    log('컨트랙트가 이미 있다 — 이 노드에서는 이미 맞춘 체인이다. 할 일 없음');
    return;
  }
  deploy();
  for (const address of [COMMIT_ANCHOR, PREDICT_TOKEN]) {
    if ((await provider.getCode(address)) === '0x') {
      throw new Error(`배포 뒤 ${address} 에 코드가 없다 — deploy.js 의 배포 순서가 바뀌었나. deployments/README.md 표와 여기 상수를 같이 고쳐라`);
    }
  }
  log(`배포 확인 — CommitAnchor ${COMMIT_ANCHOR} · PredictToken ${PREDICT_TOKEN}`);

  const env = readEnvFile(BACKEND_ENV_FILE);
  if (!env) {
    log(`${BACKEND_ENV_FILE} 가 없다 — DB 는 건드리지 않는다`);
    return;
  }
  const backendUsesThisChain =
    env.CHAIN_ID === String(LOCAL_CHAIN_ID) && (env.CONTRACT_COMMIT_ANCHOR || '').toLowerCase() === COMMIT_ANCHOR.toLowerCase();
  if (!backendUsesThisChain) {
    log(`backend/.env 가 이 로컬 체인을 보지 않는다(CHAIN_ID=${env.CHAIN_ID || '없음'}) — 다른 체인 세트의 DB 로 보고 건드리지 않는다`);
    return;
  }

  const config = dbConfig(env);
  const db = await connect(config);
  try {
    const { rows } = await db.query(`select to_regclass('public.chain_events') is not null as ready`);
    if (!rows[0].ready) {
      log(`DB ${config.database} 에 테이블이 아직 없다 — 백엔드를 한 번도 안 띄웠다. 비울 것도 다시 보낼 것도 없다`);
      return;
    }
    await wipeChainCopy(db);
    await reanchorConfirmed(db, provider);
  } finally {
    await db.end();
  }

  await warnIfBackendRunning();
  log('끝 — 백엔드를 켜면 인덱서가 0번 블록부터 읽고, 재시도 배치가 연동 회원에게 가입 보너스를 다시 준다');
}

main().catch((e) => {
  console.error('[localchain] 실패 —', e.message);
  process.exit(1);
});
