import { createRequire } from 'node:module';
import { WebSocketProvider, Wallet, ContractFactory, Contract, isAddress } from 'ethers';

const require = createRequire(import.meta.url);
const { readArtifact, readDeployment, emit, requireEnvironment } = require('./emit-artifacts.js');

/**
 * PredictionEventPool(배팅 이벤트 돈통) SSAFY 네트워크 배포 (ANT-EVENT-01).
 *
 *   npx hardhat compile
 *   DEPLOY_ENV=prod  ANCHOR_ADMIN_PRIVATE_KEY=0x…  TOKEN_OPERATOR=0x…  node scripts/deploy-pool-ssafy.mjs
 *   → 이어서  DEPLOY_ENV=prod ANCHOR_ADMIN_PRIVATE_KEY=0x… node scripts/grant-mover-ssafy.mjs   (MOVER_ROLE 부여)
 *
 * ── 주소 셋 ──────────────────────────────────────────────────────────
 *   관리자     ANCHOR_ADMIN_PRIVATE_KEY 의 주소. 배포 tx 에 서명한다. 토큰·앵커와 같은 키다.
 *   오퍼레이터 TOKEN_OPERATOR — **주소만**. 서버 릴레이어(RELAYER_PRIVATE_KEY)의 주소. 토큰의 OPERATOR_ROLE 과 같은 주소.
 *   토큰·수납 deployments/<env>/PredictToken.json 에서 읽는다(치우침 방지 — 손으로 넣지 않는다). 없으면 CONTRACT_PREDICT_TOKEN · TOKEN_TREASURY env.
 *
 * ── 배포 뒤 체인에서 직접 확인하는 것 ─────────────────────────────────
 *   token() == 토큰 주소 · treasury() == 토큰의 treasury() · 오퍼레이터에게 OPERATOR_ROLE 있음 · 관리자에겐 없음.
 *   하나라도 어긋나면 기록을 남기지 않는다.
 *
 * ── 이 체인의 함정 (deploy-token-ssafy.mjs 와 같다) ────────────────────
 *  gasPrice 0 명시 · evmVersion paris · 로컬 서명 · wss 라 ethers 로 직접 붙는다.
 *
 * ── 배포 뒤 남은 한 걸음 ─────────────────────────────────────────────
 *  풀은 ANT 를 옮길 권한이 없다. 관리자가 PredictToken.grantRole(MOVER_ROLE, pool) 을 줘야 placeBet 이 된다 → grant-mover-ssafy.mjs.
 */

const RPC_URL = process.env.CHAIN_RPC_URL || 'wss://ws.ssafy-blockchain.com';
const EXPECTED_CHAIN_ID = 31221n;
const CONTRACT = 'PredictionEventPool';

function requireEnv(name, hint) {
  const v = process.env[name];
  if (!v) {
    console.error(`${name} 가 없다. ${hint}`);
    process.exit(1);
  }
  return v;
}

function requireAddress(name, hint) {
  const v = requireEnv(name, hint);
  if (!isAddress(v)) {
    console.error(`${name} 가 주소 형식이 아니다: ${v}`);
    process.exit(1);
  }
  return v;
}

function tokenInfo(environment) {
  try {
    const rec = readDeployment(environment, 'PredictToken');
    if (rec && rec.address) return { address: rec.address, treasury: rec.treasury };
  } catch (_) {
    // readDeployment 는 기록이 없으면 throw 한다 — 그때만 env 로 받는다.
  }
  return {
    address: requireAddress('CONTRACT_PREDICT_TOKEN', `deployments/${environment}/PredictToken.json 이 없다. 토큰 주소를 env 로.`),
    treasury: requireAddress('TOKEN_TREASURY', '토큰 배포 기록이 없어 수납 주소도 env 로 받는다.'),
  };
}

async function main() {
  const environment = requireEnvironment(CONTRACT);
  const artifact = readArtifact(CONTRACT);
  const tokenArtifact = readArtifact('PredictToken');
  const adminKey = requireEnv('ANCHOR_ADMIN_PRIVATE_KEY', '관리자 키로 배포한다. 셸 환경변수로 넘겨라 — 파일에 적지 마라.');
  const operator = requireAddress('TOKEN_OPERATOR', '오퍼레이터 **주소**(키가 아니다). 서버 릴레이어 주소.');
  const token = tokenInfo(environment);

  const provider = new WebSocketProvider(RPC_URL);
  try {
    const net = await provider.getNetwork();
    if (net.chainId !== EXPECTED_CHAIN_ID) {
      throw new Error(`chainId 가 ${net.chainId} 다. 기대값은 ${EXPECTED_CHAIN_ID} — RPC 주소를 확인해라.`);
    }
    const adminWallet = new Wallet(adminKey.startsWith('0x') ? adminKey : `0x${adminKey}`, provider);
    const admin = adminWallet.address;

    // 수납 주소는 체인의 토큰에서 읽는다 — 기록과 어긋나면 체인이 진실이다.
    const tokenOnChain = new Contract(token.address, tokenArtifact.abi, provider);
    const treasury = await tokenOnChain.treasury();

    console.log(`PredictionEventPool 배포 → SSAFY ${environment} (chainId ${net.chainId})`);
    console.log(`  RPC        ${RPC_URL}`);
    console.log(`  관리자     ${admin}  (배포자)`);
    console.log(`  오퍼레이터 ${operator}`);
    console.log(`  토큰       ${token.address}`);
    console.log(`  수납       ${treasury}`);

    const factory = new ContractFactory(artifact.abi, artifact.bytecode, adminWallet);
    const contract = await factory.deploy(admin, operator, token.address, treasury, { gasPrice: 0, gasLimit: 6_000_000 });
    const tx = contract.deploymentTransaction();
    await contract.waitForDeployment();
    const receipt = await provider.getTransactionReceipt(tx.hash);
    const address = await contract.getAddress();

    const c = new Contract(address, artifact.abi, provider);
    const OPERATOR_ROLE = await c.OPERATOR_ROLE();
    const DEFAULT_ADMIN_ROLE = await c.DEFAULT_ADMIN_ROLE();
    const facts = {
      token: await c.token(),
      treasury: await c.treasury(),
      adminHasAdmin: await c.hasRole(DEFAULT_ADMIN_ROLE, admin),
      adminHasOperator: await c.hasRole(OPERATOR_ROLE, admin),
      operatorHasOperator: await c.hasRole(OPERATOR_ROLE, operator),
      poolHasMover: await tokenOnChain.hasRole(await tokenOnChain.MOVER_ROLE(), address),
    };
    console.log('  확인', facts);
    const ok =
      facts.token.toLowerCase() === token.address.toLowerCase() &&
      facts.treasury.toLowerCase() === treasury.toLowerCase() &&
      facts.adminHasAdmin &&
      !facts.adminHasOperator &&
      facts.operatorHasOperator;
    if (!ok) {
      throw new Error('풀 파라미터 또는 역할이 기대와 다르다. 위 결과를 보고 재배포해라. 기록은 남기지 않았다.');
    }

    emit(
      {
        network: 'ssafy',
        chainId: Number(net.chainId),
        address,
        deployer: admin,
        admin,
        operator,
        token: token.address,
        treasury,
        txHash: tx.hash,
        blockNumber: receipt.blockNumber,
        gasUsed: Number(receipt.gasUsed),
        moverGranted: facts.poolHasMover,
      },
      artifact,
      { environment, contractName: CONTRACT, version: 1, envVar: 'CONTRACT_EVENT_POOL' },
    );
    console.log('  다음: MOVER_ROLE 부여 —  DEPLOY_ENV=' + environment + ' ANCHOR_ADMIN_PRIVATE_KEY=0x… node scripts/grant-mover-ssafy.mjs');
  } finally {
    await provider.destroy().catch(() => {});
  }
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
