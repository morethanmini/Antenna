const { expect } = require('chai');
const { ethers } = require('hardhat');

/**
 * PredictionEventPool 단위 테스트 (ANT-EVENT-01).
 *
 * ── 규칙은 PredictToken.test.js 와 같다 ─────────────────────────────
 * revert 는 revertedWithCustomError 로 에러 이름까지 단언한다. 권한 에러로 위장 통과하는 케이스를 걸러내기 위해서다.
 *
 * ── 이 파일이 지키는 성질 ──────────────────────────────────────────────
 *  1. 상태 머신 순서를 어기는 호출은 전부 BadState 로 막힌다 — 특히 봉인 전 reveal.
 *  2. 배팅 때 ANT 가 사용자 → 풀로 실제로 이동하고, 잔액 부족이면 상태도 남지 않는다.
 *  3. 커밋 대조: 방향·noteHash 가 하나라도 다르면 BadCommit.
 *  4. 배분식: 이긴 쪽 stake × 전체 ÷ 이긴 풀, 진 쪽 0, 환불 세 경로(미리빌·TIE·이긴 풀 0)는 원금.
 *  5. 정산 합계 ≤ 풀, 잔돈은 sweep 으로 treasury. 이벤트별 escrow 라 회차끼리 안 섞인다.
 *  6. 취소 함수가 없다(설계). 이중 정산·이중 리빌·이중 판정은 revert.
 */

const { encodeBytes32String, solidityPackedKeccak256, keccak256, toUtf8Bytes } = ethers;
const REASON = encodeBytes32String('SIGNUP_BONUS');
const UP = 0;
const DOWN = 1;
const TIE = 2;
const EVENT = 1n;

/** 서버 EventCommits 가 만들 값과 같은 식. 컨트랙트 reveal 이 재계산해 대조한다. */
const commitOf = (eventId, stockIdx, side, noteHash) =>
  solidityPackedKeccak256(['uint256', 'uint16', 'uint8', 'bytes32'], [eventId, stockIdx, side, noteHash]);
const noteHashOf = (text) => keccak256(toUtf8Bytes(text));

describe('PredictionEventPool', function () {
  let token;
  let pool;
  let admin; // DEFAULT_ADMIN_ROLE (토큰·풀 공통)
  let operator; // OPERATOR_ROLE (토큰·풀 공통) = 서버 릴레이어
  let treasury;
  let alice;
  let bob;
  let carol;
  let dave;
  let stranger;
  let MOVER_ROLE;

  beforeEach(async function () {
    [admin, operator, treasury, alice, bob, carol, dave, stranger] = await ethers.getSigners();
    const tokenFactory = await ethers.getContractFactory('PredictToken');
    token = await tokenFactory.deploy(admin.address, operator.address, treasury.address);
    await token.waitForDeployment();
    const poolFactory = await ethers.getContractFactory('PredictionEventPool');
    pool = await poolFactory.deploy(admin.address, operator.address, await token.getAddress(), treasury.address);
    await pool.waitForDeployment();
    MOVER_ROLE = await token.MOVER_ROLE();
    await token.connect(admin).grantRole(MOVER_ROLE, await pool.getAddress());
    for (const u of [alice, bob, carol, dave]) {
      await token.connect(operator).mint(u.address, 10_000n, REASON);
    }
  });

  const op = () => pool.connect(operator);
  const open = (eventId = EVENT, stocks = 10, min = 500n, max = 2000n) => op().openEvent(eventId, stocks, min, max);

  /** 배팅 한 건: 커밋을 만들어 올리고 (betId, side, noteHash) 를 돌려준다. */
  async function bet(user, stockIdx, side, stake, eventId = EVENT, note = `${user.address}-${stockIdx}`) {
    const noteHash = noteHashOf(note);
    const commit = commitOf(eventId, stockIdx, side, noteHash);
    const betId = await pool.betCount(eventId);
    await op().placeBet(eventId, user.address, stockIdx, stake, commit);
    return { betId, side, noteHash, stake, user };
  }

  const revealAll = (bets, eventId = EVENT) =>
    op().reveal(
      eventId,
      bets.map((b) => b.betId),
      bets.map((b) => b.side),
      bets.map((b) => b.noteHash),
    );

  /** 삼성전자(0) 에 A 1000 UP · B 4000 없음(상한) → A 1000 UP, B 2000 UP, C 1000 DOWN. 총 4000, UP 3000, DOWN 1000. */
  async function standardBets() {
    const a = await bet(alice, 0, UP, 1000n);
    const b = await bet(bob, 0, UP, 2000n);
    const c = await bet(carol, 0, DOWN, 1000n);
    return [a, b, c];
  }

  describe('배포', function () {
    it('토큰·treasury 주소, 롤 분리, 풀은 MOVER 를 갖는다', async function () {
      expect(await pool.token()).to.equal(await token.getAddress());
      expect(await pool.treasury()).to.equal(treasury.address);
      expect(await pool.hasRole(await pool.OPERATOR_ROLE(), operator.address)).to.equal(true);
      expect(await pool.hasRole(await pool.OPERATOR_ROLE(), admin.address)).to.equal(false);
      expect(await token.hasRole(MOVER_ROLE, await pool.getAddress())).to.equal(true);
    });

    it('0 주소는 거부', async function () {
      const f = await ethers.getContractFactory('PredictionEventPool');
      await expect(f.deploy(ethers.ZeroAddress, operator.address, await token.getAddress(), treasury.address)).to.be.revertedWithCustomError(f, 'ZeroAddress');
    });
  });

  describe('openEvent · seal', function () {
    it('정상 오픈 → OPEN · 이벤트 로그', async function () {
      await expect(open()).to.emit(pool, 'EventOpened').withArgs(EVENT, 10, 500n, 2000n);
      const e = await pool.eventInfo(EVENT);
      expect(e.status).to.equal(1n);
      expect(e.stockCount).to.equal(10n);
    });

    it('같은 eventId 두 번 → BadState', async function () {
      await open();
      await expect(open()).to.be.revertedWithCustomError(pool, 'BadState');
    });

    it('종목 0개 · min 0 · min > max → BadConfig', async function () {
      await expect(op().openEvent(EVENT, 0, 500n, 2000n)).to.be.revertedWithCustomError(pool, 'BadConfig');
      await expect(op().openEvent(EVENT, 10, 0n, 2000n)).to.be.revertedWithCustomError(pool, 'BadConfig');
      await expect(op().openEvent(EVENT, 10, 3000n, 2000n)).to.be.revertedWithCustomError(pool, 'BadConfig');
    });

    it('오퍼레이터가 아니면 전 함수 거부', async function () {
      await open();
      const s = pool.connect(stranger);
      const err = 'AccessControlUnauthorizedAccount';
      await expect(s.openEvent(2n, 10, 500n, 2000n)).to.be.revertedWithCustomError(pool, err);
      await expect(s.placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, err);
      await expect(s.seal(EVENT)).to.be.revertedWithCustomError(pool, err);
      await expect(s.reveal(EVENT, [], [], [])).to.be.revertedWithCustomError(pool, err);
      await expect(s.revealDone(EVENT)).to.be.revertedWithCustomError(pool, err);
      await expect(s.resolveStock(EVENT, 0, 1n, 2n)).to.be.revertedWithCustomError(pool, err);
      await expect(s.resolveDone(EVENT)).to.be.revertedWithCustomError(pool, err);
      await expect(s.settle(EVENT, [])).to.be.revertedWithCustomError(pool, err);
      await expect(s.sweep(EVENT)).to.be.revertedWithCustomError(pool, err);
    });

    it('seal: OPEN 에서만 · 두 번째는 BadState', async function () {
      await expect(op().seal(EVENT)).to.be.revertedWithCustomError(pool, 'BadState');
      await open();
      await expect(op().seal(EVENT)).to.emit(pool, 'EventSealed').withArgs(EVENT);
      await expect(op().seal(EVENT)).to.be.revertedWithCustomError(pool, 'BadState');
    });
  });

  describe('placeBet', function () {
    beforeEach(async function () {
      await open();
    });

    it('정상: ANT 가 사용자 → 풀로, hasBet · escrow · BetPlaced', async function () {
      const noteHash = noteHashOf('x');
      const commit = commitOf(EVENT, 3, DOWN, noteHash);
      await expect(op().placeBet(EVENT, alice.address, 3, 1500n, commit))
        .to.emit(pool, 'BetPlaced')
        .withArgs(EVENT, 0n, alice.address, 3, 1500n, commit);
      expect(await token.balanceOf(alice.address)).to.equal(8_500n);
      expect(await token.balanceOf(await pool.getAddress())).to.equal(1_500n);
      expect(await pool.hasBet(EVENT, alice.address, 3)).to.equal(true);
      const e = await pool.eventInfo(EVENT);
      expect(e.betCount).to.equal(1n);
      expect(e.escrow).to.equal(1_500n);
      const b = await pool.getBet(EVENT, 0n);
      expect(b.user).to.equal(alice.address);
      expect(b.stockIdx).to.equal(3n);
      expect(b.revealed).to.equal(false);
      expect(b.commit).to.equal(commit);
    });

    it('풀 크기는 reveal 전엔 0', async function () {
      await bet(alice, 0, UP, 1000n);
      const [up, down] = await pool.getPool(EVENT, 0);
      expect(up).to.equal(0n);
      expect(down).to.equal(0n);
    });

    it('범위 밖 금액 → StakeOutOfRange (499 · 2001)', async function () {
      await expect(op().placeBet(EVENT, alice.address, 0, 499n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'StakeOutOfRange');
      await expect(op().placeBet(EVENT, alice.address, 0, 2001n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'StakeOutOfRange');
      await op().placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash);
      await op().placeBet(EVENT, alice.address, 1, 2000n, ethers.ZeroHash);
    });

    it('같은 사람 같은 종목 두 번 → AlreadyBet · 다른 종목은 된다', async function () {
      await bet(alice, 0, UP, 500n);
      await expect(op().placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'AlreadyBet');
      await bet(alice, 1, DOWN, 500n);
    });

    it('종목 번호 범위 밖 → WrongStock', async function () {
      await expect(op().placeBet(EVENT, alice.address, 10, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'WrongStock');
    });

    it('잔액 부족 → 토큰 revert, 상태도 남지 않는다', async function () {
      await token.connect(operator).burn(alice.address, 9_800n, REASON); // 200 남김
      await expect(op().placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(token, 'ERC20InsufficientBalance');
      expect(await pool.hasBet(EVENT, alice.address, 0)).to.equal(false);
      expect(await pool.betCount(EVENT)).to.equal(0n);
      expect((await pool.eventInfo(EVENT)).escrow).to.equal(0n);
    });

    it('MOVER 가 없으면 배팅이 안 된다 — 배포 뒤 grantRole 이 빠지면 여기서 드러난다', async function () {
      await token.connect(admin).revokeRole(MOVER_ROLE, await pool.getAddress());
      await expect(op().placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(token, 'AccessControlUnauthorizedAccount');
    });

    it('봉인 뒤 → BadState', async function () {
      await op().seal(EVENT);
      await expect(op().placeBet(EVENT, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'BadState');
    });

    it('열지 않은 이벤트 → BadState(NONE)', async function () {
      await expect(op().placeBet(2n, alice.address, 0, 500n, ethers.ZeroHash)).to.be.revertedWithCustomError(pool, 'BadState').withArgs(2n, 0);
    });
  });

  describe('reveal', function () {
    let bets;
    beforeEach(async function () {
      await open();
      bets = await standardBets();
    });

    it('봉인 전 reveal → BadState — 접수 중 방향 유출을 코드가 막는다', async function () {
      await expect(revealAll(bets)).to.be.revertedWithCustomError(pool, 'BadState');
    });

    it('정상: 풀 누적 · BetRevealed · side 기록', async function () {
      await op().seal(EVENT);
      await expect(revealAll(bets)).to.emit(pool, 'BetRevealed').withArgs(EVENT, 0n, UP);
      const [up, down] = await pool.getPool(EVENT, 0);
      expect(up).to.equal(3_000n);
      expect(down).to.equal(1_000n);
      expect((await pool.getBet(EVENT, 2n)).side).to.equal(BigInt(DOWN));
      expect((await pool.getBet(EVENT, 2n)).revealed).to.equal(true);
    });

    it('방향이 다르면 BadCommit', async function () {
      await op().seal(EVENT);
      await expect(op().reveal(EVENT, [bets[0].betId], [DOWN], [bets[0].noteHash])).to.be.revertedWithCustomError(pool, 'BadCommit');
    });

    it('noteHash 가 다르면 BadCommit', async function () {
      await op().seal(EVENT);
      await expect(op().reveal(EVENT, [bets[0].betId], [UP], [noteHashOf('other')])).to.be.revertedWithCustomError(pool, 'BadCommit');
    });

    it('side 값 2 이상 → BadCommit', async function () {
      await op().seal(EVENT);
      await expect(op().reveal(EVENT, [bets[0].betId], [2], [bets[0].noteHash])).to.be.revertedWithCustomError(pool, 'BadCommit');
    });

    it('길이 불일치 → LengthMismatch · 없는 betId → NoSuchBet · 이중 리빌 → AlreadyRevealed', async function () {
      await op().seal(EVENT);
      await expect(op().reveal(EVENT, [0n], [UP, DOWN], [bets[0].noteHash])).to.be.revertedWithCustomError(pool, 'LengthMismatch');
      await expect(op().reveal(EVENT, [99n], [UP], [bets[0].noteHash])).to.be.revertedWithCustomError(pool, 'NoSuchBet');
      await revealAll([bets[0]]);
      await expect(revealAll([bets[0]])).to.be.revertedWithCustomError(pool, 'AlreadyRevealed');
    });

    it('한 건이 틀리면 tx 전체가 되감긴다(부분 반영 없음)', async function () {
      await op().seal(EVENT);
      await expect(op().reveal(EVENT, [0n, 1n], [UP, DOWN], [bets[0].noteHash, bets[1].noteHash])).to.be.revertedWithCustomError(pool, 'BadCommit');
      expect((await pool.getBet(EVENT, 0n)).revealed).to.equal(false);
      const [up] = await pool.getPool(EVENT, 0);
      expect(up).to.equal(0n);
    });

    it('revealDone 뒤 reveal → BadState · resolve 전 revealDone 은 두 번 안 된다', async function () {
      await op().seal(EVENT);
      await revealAll(bets.slice(0, 2));
      await expect(op().revealDone(EVENT)).to.emit(pool, 'EventRevealed');
      await expect(revealAll([bets[2]])).to.be.revertedWithCustomError(pool, 'BadState');
      await expect(op().revealDone(EVENT)).to.be.revertedWithCustomError(pool, 'BadState');
    });
  });

  describe('resolveStock · resolveDone', function () {
    beforeEach(async function () {
      await open(EVENT, 2);
      await bet(alice, 0, UP, 1000n);
      await bet(bob, 1, DOWN, 1000n);
      await op().seal(EVENT);
    });

    it('REVEALED 전엔 BadState', async function () {
      await expect(op().resolveStock(EVENT, 0, 100n, 110n)).to.be.revertedWithCustomError(pool, 'BadState');
    });

    it('UP · DOWN · TIE 판정과 이벤트 로그', async function () {
      await op().revealDone(EVENT);
      await expect(op().resolveStock(EVENT, 0, 100n, 110n)).to.emit(pool, 'StockResolved').withArgs(EVENT, 0, 100n, 110n, UP);
      await expect(op().resolveStock(EVENT, 1, 100n, 100n)).to.emit(pool, 'StockResolved').withArgs(EVENT, 1, 100n, 100n, TIE);
      expect((await pool.getResult(EVENT, 0)).winner).to.equal(BigInt(UP));
      expect((await pool.getResult(EVENT, 1)).winner).to.equal(BigInt(TIE));
    });

    it('이중 판정 → AlreadyResolved · 범위 밖 → WrongStock · 가격 0 → BadConfig', async function () {
      await op().revealDone(EVENT);
      await op().resolveStock(EVENT, 0, 100n, 90n);
      await expect(op().resolveStock(EVENT, 0, 100n, 90n)).to.be.revertedWithCustomError(pool, 'AlreadyResolved');
      await expect(op().resolveStock(EVENT, 2, 100n, 90n)).to.be.revertedWithCustomError(pool, 'WrongStock');
      await expect(op().resolveStock(EVENT, 1, 0n, 90n)).to.be.revertedWithCustomError(pool, 'BadConfig');
    });

    it('resolveDone: 전 종목 판정 전엔 NotAllResolved, 뒤엔 RESOLVED', async function () {
      await op().revealDone(EVENT);
      await op().resolveStock(EVENT, 0, 100n, 110n);
      await expect(op().resolveDone(EVENT)).to.be.revertedWithCustomError(pool, 'NotAllResolved').withArgs(1, 2);
      await op().resolveStock(EVENT, 1, 100n, 90n);
      await expect(op().resolveDone(EVENT)).to.emit(pool, 'EventResolved');
      expect((await pool.eventInfo(EVENT)).status).to.equal(4n);
    });
  });

  describe('settle · sweep — 배분식', function () {
    let bets;
    const poolAddr = () => pool.getAddress();

    async function runToResolved(base, close, revealSubset) {
      await op().seal(EVENT);
      await revealAll(revealSubset ?? bets);
      await op().revealDone(EVENT);
      await op().resolveStock(EVENT, 0, base, close);
      await op().resolveDone(EVENT);
    }

    beforeEach(async function () {
      await open(EVENT, 1);
      bets = await standardBets(); // A 1000 UP · B 2000 UP · C 1000 DOWN → 총 4000
    });

    it('UP 승: A 1333 · B 2666 · C 0 · 잔돈 1 → treasury', async function () {
      await runToResolved(100n, 110n);
      expect(await pool.previewPayout(EVENT, 0n)).to.deep.equal([1333n, false]);
      await expect(op().settle(EVENT, [0n, 1n, 2n]))
        .to.emit(pool, 'BetSettled').withArgs(EVENT, 0n, alice.address, 1333n)
        .and.to.emit(pool, 'BetSettled').withArgs(EVENT, 1n, bob.address, 2666n)
        .and.to.emit(pool, 'BetSettled').withArgs(EVENT, 2n, carol.address, 0n);
      expect(await token.balanceOf(alice.address)).to.equal(10_000n - 1000n + 1333n);
      expect(await token.balanceOf(bob.address)).to.equal(10_000n - 2000n + 2666n);
      expect(await token.balanceOf(carol.address)).to.equal(9_000n);
      expect((await pool.eventInfo(EVENT)).escrow).to.equal(1n);
      await expect(op().sweep(EVENT)).to.emit(pool, 'EventSettled').withArgs(EVENT, 1n);
      expect(await token.balanceOf(treasury.address)).to.equal(1n);
      expect(await token.balanceOf(await poolAddr())).to.equal(0n);
      expect((await pool.eventInfo(EVENT)).status).to.equal(5n);
    });

    it('DOWN 승: C 가 전체 4000 · A·B 0', async function () {
      await runToResolved(100n, 90n);
      await op().settle(EVENT, [0n, 1n, 2n]);
      expect(await token.balanceOf(carol.address)).to.equal(10_000n - 1000n + 4000n);
      expect(await token.balanceOf(alice.address)).to.equal(9_000n);
      expect(await token.balanceOf(bob.address)).to.equal(8_000n);
      await expect(op().sweep(EVENT)).to.emit(pool, 'EventSettled').withArgs(EVENT, 0n);
    });

    it('보합(TIE): 전원 원금 환불 · BetRefunded', async function () {
      await runToResolved(100n, 100n);
      await expect(op().settle(EVENT, [0n, 1n, 2n]))
        .to.emit(pool, 'BetRefunded').withArgs(EVENT, 0n, alice.address, 1000n)
        .and.to.emit(pool, 'BetRefunded').withArgs(EVENT, 2n, carol.address, 1000n);
      for (const u of [alice, bob, carol]) expect(await token.balanceOf(u.address)).to.equal(10_000n);
      await op().sweep(EVENT);
      expect(await token.balanceOf(await poolAddr())).to.equal(0n);
    });

    it('이긴 풀이 0(전원 한쪽): 원금 환불', async function () {
      // 아무도 DOWN 을 안 밝혔는데 DOWN 이 이긴 경우 — C 를 리빌하지 않는다.
      await runToResolved(100n, 90n, bets.slice(0, 2));
      await expect(op().settle(EVENT, [0n, 1n])).to.emit(pool, 'BetRefunded').withArgs(EVENT, 0n, alice.address, 1000n);
      // C 는 미리빌이라 역시 환불
      await expect(op().settle(EVENT, [2n])).to.emit(pool, 'BetRefunded').withArgs(EVENT, 2n, carol.address, 1000n);
      for (const u of [alice, bob, carol]) expect(await token.balanceOf(u.address)).to.equal(10_000n);
    });

    it('미리빌 배팅은 풀 밖: 나머지끼리 배분, 본인은 환불', async function () {
      // A 를 리빌하지 않는다. UP 풀 2000(B), DOWN 1000(C). UP 승 → B = 2000×3000÷2000 = 3000, A 환불.
      await runToResolved(100n, 110n, bets.slice(1));
      await expect(op().settle(EVENT, [0n, 1n, 2n]))
        .to.emit(pool, 'BetRefunded').withArgs(EVENT, 0n, alice.address, 1000n)
        .and.to.emit(pool, 'BetSettled').withArgs(EVENT, 1n, bob.address, 3000n);
      expect(await token.balanceOf(alice.address)).to.equal(10_000n);
      expect(await token.balanceOf(bob.address)).to.equal(11_000n);
      expect(await token.balanceOf(carol.address)).to.equal(9_000n);
    });

    it('RESOLVED 전 settle · 이중 정산 · 없는 bet · sweep 조기 호출', async function () {
      await expect(op().settle(EVENT, [0n])).to.be.revertedWithCustomError(pool, 'BadState');
      await runToResolved(100n, 110n);
      await op().settle(EVENT, [0n]);
      await expect(op().settle(EVENT, [0n])).to.be.revertedWithCustomError(pool, 'AlreadySettled');
      await expect(op().settle(EVENT, [9n])).to.be.revertedWithCustomError(pool, 'NoSuchBet');
      await expect(op().sweep(EVENT)).to.be.revertedWithCustomError(pool, 'NotAllSettled').withArgs(1n, 3n);
      await op().settle(EVENT, [1n, 2n]);
      await op().sweep(EVENT);
      await expect(op().sweep(EVENT)).to.be.revertedWithCustomError(pool, 'BadState');
      await expect(op().settle(EVENT, [])).to.be.revertedWithCustomError(pool, 'BadState');
    });

    it('묶음 안에 하나가 틀리면 전체 되감김', async function () {
      await runToResolved(100n, 110n);
      await expect(op().settle(EVENT, [0n, 99n])).to.be.revertedWithCustomError(pool, 'NoSuchBet');
      expect((await pool.getBet(EVENT, 0n)).settled).to.equal(false);
      expect(await token.balanceOf(alice.address)).to.equal(9_000n);
    });

    it('previewPayout 은 RESOLVED 전엔 BadState', async function () {
      await expect(pool.previewPayout(EVENT, 0n)).to.be.revertedWithCustomError(pool, 'BadState');
    });
  });

  describe('여러 이벤트 · 여러 종목', function () {
    it('이벤트 1 과 2 의 escrow 가 섞이지 않는다 — sweep 이 남의 회차 돈을 못 가져간다', async function () {
      await open(1n, 1);
      await open(2n, 1);
      const a1 = await bet(alice, 0, UP, 1000n, 1n);
      const b2 = await bet(bob, 0, UP, 2000n, 2n);
      await op().seal(1n);
      await revealAll([a1], 1n);
      await op().revealDone(1n);
      await op().resolveStock(1n, 0, 100n, 110n);
      await op().resolveDone(1n);
      await op().settle(1n, [0n]);
      await expect(op().sweep(1n)).to.emit(pool, 'EventSettled').withArgs(1n, 0n);
      // 이벤트 2 의 2000 은 그대로 풀에 있다
      expect(await token.balanceOf(await pool.getAddress())).to.equal(2_000n);
      expect((await pool.eventInfo(2n)).escrow).to.equal(2_000n);
      expect(b2.stake).to.equal(2000n);
    });

    it('종목 10개 · 참가자 4명 · 배분 합계 ≤ 풀 · 정산 뒤 풀 잔액 = 잔돈', async function () {
      await open(EVENT, 10);
      const all = [];
      const users = [alice, bob, carol, dave];
      for (let s = 0; s < 10; s++) {
        for (let u = 0; u < users.length; u++) {
          const side = (s + u) % 2 === 0 ? UP : DOWN;
          all.push(await bet(users[u], s, side, 500n + 100n * BigInt((s + u) % 4), EVENT, `n-${s}-${u}`));
        }
      }
      await op().seal(EVENT);
      // 100건 묶음 흉내: 절반씩
      await revealAll(all.slice(0, 20));
      await revealAll(all.slice(20));
      await op().revealDone(EVENT);
      for (let s = 0; s < 10; s++) {
        const close = s % 3 === 0 ? 110n : s % 3 === 1 ? 90n : 100n; // UP / DOWN / TIE 섞기
        await op().resolveStock(EVENT, s, 100n, close);
      }
      await op().resolveDone(EVENT);
      const escrowBefore = (await pool.eventInfo(EVENT)).escrow;
      const ids = all.map((b) => b.betId);
      await op().settle(EVENT, ids.slice(0, 20));
      await op().settle(EVENT, ids.slice(20));
      const e = await pool.eventInfo(EVENT);
      expect(e.settledCount).to.equal(40n);
      const dust = e.escrow;
      expect(dust).to.be.lessThan(escrowBefore);
      await expect(op().sweep(EVENT)).to.emit(pool, 'EventSettled').withArgs(EVENT, dust);
      expect(await token.balanceOf(await pool.getAddress())).to.equal(0n);
      // 총 유통량 보존: 사용자 4명 + treasury = 40,000
      let total = await token.balanceOf(treasury.address);
      for (const u of users) total += await token.balanceOf(u.address);
      expect(total).to.equal(40_000n);
    });
  });

  describe('가스 참고값 (CI 단언 아님 — 진행상황.md 에 적는다)', function () {
    it('placeBet 1건 · reveal 40건 · settle 40건', async function () {
      await open(EVENT, 10);
      const users = [alice, bob, carol, dave];
      const all = [];
      let placeGas = 0n;
      for (let s = 0; s < 10; s++) {
        for (const u of users) {
          const noteHash = noteHashOf(`${s}-${u.address}`);
          const commit = commitOf(EVENT, s, UP, noteHash);
          const betId = await pool.betCount(EVENT);
          const tx = await op().placeBet(EVENT, u.address, s, 500n, commit);
          placeGas = (await tx.wait()).gasUsed;
          all.push({ betId, side: UP, noteHash });
        }
      }
      await op().seal(EVENT);
      const revealTx = await revealAll(all);
      const revealGas = (await revealTx.wait()).gasUsed;
      await op().revealDone(EVENT);
      for (let s = 0; s < 10; s++) await op().resolveStock(EVENT, s, 100n, 110n);
      await op().resolveDone(EVENT);
      const settleTx = await op().settle(EVENT, all.map((b) => b.betId));
      const settleGas = (await settleTx.wait()).gasUsed;
      console.log(`      gas — placeBet ${placeGas} · reveal×40 ${revealGas} · settle×40 ${settleGas}`);
      expect(revealGas).to.be.greaterThan(0n);
    });
  });
});
