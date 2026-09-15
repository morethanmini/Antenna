import { createRequire } from 'node:module';
import { WebSocketProvider, Wallet, Contract } from 'ethers';

const require = createRequire(import.meta.url);
const { readArtifact, readDeployment } = require('./emit-artifacts.js');

/**
 * PredictToken.grantRole(MOVER_ROLE, PredictionEventPool) — 풀이 사용자 ANT 를 옮길 수 있게 (ANT-EVENT-01).
 *
 *   DEPLOY_ENV=prod  ANCHOR_ADMIN_PRIVATE_KEY=0x…  node scripts/grant-mover-ssafy.mjs
 *
 * 관리자 키만 할 수 있다(토큰의 DEFAULT_ADMIN_ROLE). 서버 키(오퍼레이터)로는 안 된다 — "서버가 스스로 이체 권한을 못 만든다"는 보장.
 * 이미 부여돼 있으면 tx 를 보내지 않는다. 끝나면 hasRole 을 다시 읽어 출력한다.
 *
 * 되돌리기: 같은 키로 revokeRole(MOVER_ROLE, pool). 풀에 남은 escrow 는 그 뒤 못 나온다 — 정산이 끝난 뒤에만.
 */

const RPC_URL = process.env.CHAIN_RPC_URL || 'wss://ws.ssafy-blockchain.com';
const EXPECTED_CHAIN_ID = 31221n;

async function main() {
  const environment = process.env.DEPLOY_ENV;
  if (!environment) {
    console.error('DEPLOY_ENV 가 없다 (dev | prod).');
    process.exit(1);
  }
  const adminKey = process.env.ANCHOR_ADMIN_PRIVATE_KEY;
  if (!adminKey) {
    console.error('ANCHOR_ADMIN_PRIVATE_KEY 가 없다. 관리자 키로만 롤을 줄 수 있다.');
    process.exit(1);
  }
  let token;
  let pool;
  try {
    token = readDeployment(environment, 'PredictToken');
    pool = readDeployment(environment, 'PredictionEventPool');
  } catch (e) {
    console.error(`deployments/${environment}/ 에 PredictToken.json 과 PredictionEventPool.json 이 둘 다 있어야 한다. ${e.message}`);
    process.exit(1);
  }

  const provider = new WebSocketProvider(RPC_URL);
  try {
    const net = await provider.getNetwork();
    if (net.chainId !== EXPECTED_CHAIN_ID) {
      throw new Error(`chainId 가 ${net.chainId} 다. 기대값은 ${EXPECTED_CHAIN_ID}.`);
    }
    const admin = new Wallet(adminKey.startsWith('0x') ? adminKey : `0x${adminKey}`, provider);
    const c = new Contract(token.address, readArtifact('PredictToken').abi, admin);
    const MOVER_ROLE = await c.MOVER_ROLE();
    const DEFAULT_ADMIN_ROLE = await c.DEFAULT_ADMIN_ROLE();
    if (!(await c.hasRole(DEFAULT_ADMIN_ROLE, admin.address))) {
      throw new Error(`${admin.address} 는 토큰 관리자가 아니다. 롤을 줄 수 없다.`);
    }
    console.log(`MOVER_ROLE 부여 → ${environment}  토큰 ${token.address}  풀 ${pool.address}`);
    if (await c.hasRole(MOVER_ROLE, pool.address)) {
      console.log('  이미 부여돼 있다. tx 를 보내지 않는다.');
      return;
    }
    const tx = await c.grantRole(MOVER_ROLE, pool.address, { gasPrice: 0, gasLimit: 200_000 });
    console.log(`  tx ${tx.hash}`);
    const receipt = await tx.wait();
    console.log(`  블록 ${receipt.blockNumber} · status ${receipt.status}`);
    console.log(`  hasRole(MOVER_ROLE, pool) = ${await c.hasRole(MOVER_ROLE, pool.address)}`);
    console.log('  다음: deployments/README.md 표에 "MOVER_ROLE 부여 tx" 를 적어라');
  } finally {
    await provider.destroy().catch(() => {});
  }
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
