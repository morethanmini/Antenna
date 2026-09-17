const fs = require('node:fs');
const path = require('node:path');
const hre = require('hardhat');

/**
 * 로컬 체인에 PredictionEventPool **하나만** 올린다 (ANT-EVENT-01).
 *
 *   npx hardhat run --network localhost scripts/deploy-pool-local.js
 *
 * ── 왜 deploy.js 를 다시 돌리지 않나 ─────────────────────────────────
 * deploy.js 는 CommitAnchor·PredictToken·Pool 셋을 nonce 0·1·2 에 순서대로 올려 주소를
 * 고정한다. 그런데 이미 배포한 체인에서 다시 돌리면 배포자 nonce 가 0 이 아니라 **주소가
 * 전부 달라진다** — backend/.env 의 토큰 주소가 가리키던 컨트랙트가 고아가 되고, 그동안
 * 쌓인 잔액·앵커 기록이 통째로 끊긴다.
 *
 * 그래서 이 스크립트는 **토큰·앵커를 건드리지 않는다.** 기존 토큰 주소를 backend/.env 에서
 * 읽어 그대로 쓰고, 풀만 새 주소로 올린다.
 *
 * ── operator 를 백엔드 릴레이어 주소로 맞춘다 ────────────────────────
 * openEvent·seal·placeBet 을 보내는 것은 백엔드의 RELAYER_PRIVATE_KEY 다. 컨트랙트의
 * operator 가 그 주소가 아니면 전송이 권한으로 되돌아가고, 증상은 "회차가 영영 안 열린다"
 * 로만 보인다(open_tx_hash 가 계속 null). 그래서 여기서 키에서 주소를 뽑아 맞춘다 —
 * 키 자체는 찍지 않는다.
 *
 * ── MOVER_ROLE 을 함께 준다 ──────────────────────────────────────────
 * 풀이 사용자 ANT 를 옮기려면 토큰의 MOVER_ROLE 이 필요하다. 없으면 회차는 열리는데
 * placeBet 이 revert 해서, 배팅만 전부 실패하는 더 헷갈리는 상태가 된다.
 */
async function main() {
  const { ethers, network } = hre;

  const envPath = path.resolve(__dirname, '../../backend/.env');
  const env = fs.readFileSync(envPath, 'utf8').split(/\r?\n/);
  const read = (key) => {
    const line = env.find((l) => l.startsWith(`${key}=`));
    return line ? line.slice(key.length + 1).trim() : '';
  };

  const tokenAddress = read('CONTRACT_PREDICT_TOKEN');
  const relayerKey = read('RELAYER_PRIVATE_KEY');
  if (!tokenAddress) throw new Error('backend/.env 에 CONTRACT_PREDICT_TOKEN 이 없다');
  if (!relayerKey) throw new Error('backend/.env 에 RELAYER_PRIVATE_KEY 가 없다');

  const [deployer, , third] = await ethers.getSigners();
  const admin = deployer.address;
  const operator = new ethers.Wallet(relayerKey).address;
  const treasury = process.env.TOKEN_TREASURY || (third ? third.address : deployer.address);
  const chainId = Number((await ethers.provider.getNetwork()).chainId);

  console.log(`풀만 배포 → ${network.name} (chainId ${chainId})`);
  console.log(`  배포자·관리자 ${admin}`);
  console.log(`  operator      ${operator}   ← backend/.env 의 릴레이어`);
  console.log(`  토큰          ${tokenAddress}`);
  console.log(`  수납          ${treasury}`);

  /* 토큰이 실제로 그 자리에 있는지 먼저 본다. 주소만 맞고 코드가 없으면 풀은 올라가지만
     placeBet 이 전부 실패한다 — 그때 원인을 찾기가 훨씬 어렵다. */
  if ((await ethers.provider.getCode(tokenAddress)) === '0x') {
    throw new Error(`토큰 주소에 코드가 없다(${tokenAddress}). 체인을 다시 띄웠다면 deploy.js 부터 돌려야 한다`);
  }

  const poolFactory = await ethers.getContractFactory('PredictionEventPool');
  const pool = await poolFactory.deploy(admin, operator, tokenAddress, treasury);
  await pool.waitForDeployment();
  const poolAddress = await pool.getAddress();
  console.log(`\n  풀 배포됨   ${poolAddress}`);

  const token = await ethers.getContractAt('PredictToken', tokenAddress);
  const MOVER_ROLE = await token.MOVER_ROLE();
  let moverGranted = await token.hasRole(MOVER_ROLE, poolAddress);
  if (!moverGranted) {
    /* 배포자가 토큰의 관리자가 아니면 여기서 실패한다 — 그건 조용히 넘길 일이 아니다.
       MOVER_ROLE 없이 두면 배팅이 전부 revert 한다. */
    await (await token.connect(deployer).grantRole(MOVER_ROLE, poolAddress)).wait();
    moverGranted = await token.hasRole(MOVER_ROLE, poolAddress);
  }
  console.log(`  MOVER_ROLE  ${moverGranted ? '부여됨' : '⚠ 실패 — 토큰 관리자 계정으로 직접 줘야 한다'}`);

  console.log('\n── backend/.env 에 이 줄을 추가하고 백엔드를 재시작하세요 ──');
  console.log(`CONTRACT_EVENT_POOL=${poolAddress}`);
}

main().catch((e) => {
  console.error(e);
  process.exitCode = 1;
});
