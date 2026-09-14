import { spawn } from 'node:child_process';

/**
 * 로컬 체인 컨테이너의 시작점 (ANT-CHAIN-15) — Dockerfile.localchain 의 CMD.
 *
 *   ① Hardhat 노드를 띄운다(0.0.0.0:8545 — 호스트의 백엔드·브라우저가 포트 매핑으로 붙는다)
 *   ② localchain-sync.mjs — 컨트랙트 배포 · 로컬 DB 체인 사본 비우기 · 확정 앵커 재전송
 *   ③ 노드가 끝날 때까지 붙어 있는다
 *
 * 셸 스크립트로 두지 않은 이유: 윈도에서 체크아웃하면 줄끝이 CRLF 로 바뀌어 리눅스 sh 가 "not found" 로 죽는다. node 는 상관없다.
 * 노드와 동기화를 한 컨테이너에 두는 이유: Hardhat 노드는 컨테이너가 다시 뜰 때마다 새 체인이다 — 동기화가 늘 그 직후에 돌아야 한다.
 * 동기화가 실패해도 노드는 살려 둔다. 체인이 떠 있어야 로그를 보고 손으로 다시 돌릴 수 있다.
 */

const node = spawn('npx', ['hardhat', 'node', '--hostname', '0.0.0.0', '--port', '8545'], { stdio: 'inherit' });
node.on('exit', (code, signal) => {
  console.log(`[localchain] Hardhat 노드 종료 (code ${code ?? '-'}, signal ${signal ?? '-'})`);
  process.exit(code ?? 0);
});

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => node.kill(sig));
}

// 동기화는 노드가 RPC 를 열 때까지 스스로 기다린다.
const sync = spawn('node', ['scripts/localchain-sync.mjs'], { stdio: 'inherit' });
sync.on('exit', (code) => {
  if (code !== 0) {
    console.error('[localchain] 동기화 실패 — 체인은 떠 있다. 위 로그를 보고 다시 돌린다: docker compose exec chain npm run localchain:sync');
  }
});
