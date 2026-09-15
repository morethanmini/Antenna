// SPDX-License-Identifier: MIT
pragma solidity 0.8.28;

import {AccessControl} from "@openzeppelin/contracts/access/AccessControl.sol";

/// PredictToken 에서 이 컨트랙트가 쓰는 두 함수만. 전체 ABI 를 끌어오지 않는다 — 여기가 의존하는 표면을 좁게 보이려고.
interface IPredictToken {
    /// MOVER_ROLE 전용 이체. 이 컨트랙트 주소에 그 롤이 있어야 한다(배포 뒤 관리자가 1회 부여).
    function operatorTransfer(address from, address to, uint256 amount) external;
}

/**
 * @title PredictionEventPool — 업앤다운 배팅 이벤트의 돈통 (ANT-EVENT-01)
 * @notice 종목별 UP 풀·DOWN 풀에 ANT 를 걸고, 판정 뒤 이긴 쪽이 전체를 배팅 비율대로 나눠 받는다(승자투표권 방식, 환급률 100%).
 *
 * ── 이 컨트랙트가 맡는 것 / 서버가 맡는 것 ─────────────────────────────
 *
 *   컨트랙트: 배팅금 보관(에스크로) · 방향 커밋 대조 · 승패 판정 · 배분 계산 · 송금. 규칙이 코드라 서버가 못 바꾼다.
 *   서버    : 기준가·종가를 넣는다(오라클). 접수 마감 시각을 지킨다(seal). 순위는 서버가 센다.
 *
 * ── 왜 커밋-리빌인가 ────────────────────────────────────────────────────
 *
 * 방향은 판정 전까지 비공개(PRED-07 규칙)다. 배팅 때는 keccak256(abi.encodePacked(eventId, stockIdx, uint8 side, noteHash))
 * 와 금액만 올리고, 서버가 종가를 확보한 뒤 reveal 로 방향을 낸다. noteHash 는 예측 커밋의 난수 재료(keccak(note ‖ noteSalt))라
 * 방향 두 가지만 해시해도 사전 공격이 안 된다. 접수 중엔 풀 크기를 이 컨트랙트가 모른다 — 화면 비율은 서버 집계다.
 *
 * ── 상태 머신 (순서를 코드가 강제한다) ───────────────────────────────────
 *
 *   NONE → openEvent → OPEN → seal → SEALED → reveal… → revealDone → REVEALED
 *        → resolveStock ×stockCount → resolveDone → RESOLVED → settle… → sweep → SETTLED
 *
 *   봉인 전 reveal 은 revert — 접수 중 방향 유출을 코드가 막는다.
 *   revealDone 뒤에도 안 밝혀진 배팅은 풀 밖이고, settle 에서 원금을 돌려받는다(DB 유실 대비 안전장치. 정상 흐름에선 없다).
 *   취소·종목 무효·비상 인출은 없다(09-15 결정). 정산 버튼이 안 눌리는 버그면 돈이 갇힌다 — 그래서 테스트가 두껍다.
 *
 * ── 배분 ───────────────────────────────────────────────────────────────
 *
 *   이긴 쪽: payout = stake × (poolUp + poolDown) / poolWinner  (곱셈 먼저)
 *   진 쪽  : 0
 *   환불   : 미리빌 · 보합(TIE) · 이긴 풀이 0 → payout = stake
 *   정수 절삭 나머지는 이벤트별 escrow 에 남고 sweep 이 treasury 로 보낸다.
 *
 * ── 왜 팩토리가 아닌가 ──────────────────────────────────────────────────
 *
 * MOVER_ROLE 은 주소 단위라 이벤트마다 컨트랙트를 찍으면 회차마다 관리자 키 tx 가 필요하다. 하나에 eventId 차원을 두면
 * 배포 때 한 번이다. 대가: 모든 회차의 자금이 한 주소에 모인다. 이벤트별 escrow 를 따로 세어 서로 섞이지 않게 한다.
 *
 * ── 단위 ───────────────────────────────────────────────────────────────
 *
 *   금액은 정수 ANT(decimals 0). 가격은 서버가 ×100 정수로 넣는다 — 여기서는 크기 비교만 하므로 단위가 같기만 하면 된다.
 */
contract PredictionEventPool is AccessControl {
    /// 서버 릴레이어. 상태를 바꾸는 함수 전부. 사용자는 이 컨트랙트에 tx 를 보내지 않는다(지갑은 EIP-191 서명만).
    bytes32 public constant OPERATOR_ROLE = keccak256("OPERATOR_ROLE");

    enum Status {
        NONE,
        OPEN,
        SEALED,
        REVEALED,
        RESOLVED,
        SETTLED
    }

    enum Side {
        UP,
        DOWN
    }

    /// StockResult.winner 값. Side 와 같은 번호에 TIE 를 더한 것.
    uint8 public constant WINNER_UP = 0;
    uint8 public constant WINNER_DOWN = 1;
    uint8 public constant WINNER_TIE = 2;

    struct EventInfo {
        Status status;
        uint16 stockCount;
        uint16 resolvedCount;
        uint256 minStake;
        uint256 maxStake;
        uint256 betCount;
        uint256 settledCount;
        /// 이 이벤트 몫으로 이 컨트랙트가 들고 있는 ANT. placeBet 에서 늘고 송금에서 준다. balanceOf(this) 는 전 회차 합이라 못 쓴다.
        uint256 escrow;
    }

    struct Bet {
        address user;
        uint16 stockIdx;
        bool revealed;
        bool settled;
        Side side;
        uint256 stake;
        bytes32 commit;
        uint256 payout;
    }

    struct StockResult {
        bool resolved;
        uint8 winner;
        uint256 basePrice;
        uint256 closePrice;
    }

    IPredictToken public immutable token;
    /// 잔돈 회수처. 토큰의 treasury 와 같은 주소를 넣는다(배포 스크립트가 확인).
    address public immutable treasury;

    mapping(uint256 eventId => EventInfo) private _events;
    mapping(uint256 eventId => Bet[]) private _bets;
    /// [UP, DOWN] 풀. reveal 때만 늘어난다.
    mapping(uint256 eventId => mapping(uint16 stockIdx => uint256[2])) private _pool;
    mapping(uint256 eventId => mapping(uint16 stockIdx => StockResult)) private _results;
    /// 종목당 1건(09-15 결정). 서버도 UNIQUE 로 막지만 여기가 최종.
    mapping(uint256 eventId => mapping(address user => mapping(uint16 stockIdx => bool))) public hasBet;

    // ── 이벤트 (인덱서 ③ 가 구독한다) ─────────────────────────────────────
    event EventOpened(uint256 indexed eventId, uint16 stockCount, uint256 minStake, uint256 maxStake);
    event BetPlaced(
        uint256 indexed eventId, uint256 indexed betId, address indexed user, uint16 stockIdx, uint256 stake, bytes32 commit
    );
    event EventSealed(uint256 indexed eventId);
    event BetRevealed(uint256 indexed eventId, uint256 indexed betId, uint8 side);
    event EventRevealed(uint256 indexed eventId);
    event StockResolved(uint256 indexed eventId, uint16 indexed stockIdx, uint256 basePrice, uint256 closePrice, uint8 winner);
    event EventResolved(uint256 indexed eventId);
    event BetSettled(uint256 indexed eventId, uint256 indexed betId, address indexed user, uint256 payout);
    event BetRefunded(uint256 indexed eventId, uint256 indexed betId, address indexed user, uint256 stake);
    event EventSettled(uint256 indexed eventId, uint256 dust);

    // ── 에러 ─────────────────────────────────────────────────────────────
    /// 그 상태에서 부를 수 없는 함수. 어느 상태였는지를 실어 서버가 "이미 끝난 단계"를 건너뛸 수 있게 한다.
    error BadState(uint256 eventId, Status current);
    error ZeroAddress();
    error BadConfig();
    error WrongStock(uint16 stockIdx);
    error StakeOutOfRange(uint256 stake, uint256 minStake, uint256 maxStake);
    error AlreadyBet(address user, uint16 stockIdx);
    error NoSuchBet(uint256 betId);
    error AlreadyRevealed(uint256 betId);
    error BadCommit(uint256 betId);
    error LengthMismatch();
    error AlreadyResolved(uint16 stockIdx);
    error NotAllResolved(uint16 resolved, uint16 total);
    error AlreadySettled(uint256 betId);
    error NotAllSettled(uint256 settled, uint256 total);

    /**
     * @param admin     롤 부여·회수. 잔액은 못 건드린다. 토큰과 같은 관리자 키.
     * @param operator  서버 릴레이어 주소. 토큰의 OPERATOR_ROLE 과 같은 주소를 쓴다.
     * @param token_    PredictToken(ANT). 재배포되지 않는 주소.
     * @param treasury_ 잔돈 회수처. 토큰의 treasury 와 같게.
     */
    constructor(address admin, address operator, address token_, address treasury_) {
        if (admin == address(0) || operator == address(0) || token_ == address(0) || treasury_ == address(0)) {
            revert ZeroAddress();
        }
        _grantRole(DEFAULT_ADMIN_ROLE, admin);
        _grantRole(OPERATOR_ROLE, operator);
        token = IPredictToken(token_);
        treasury = treasury_;
    }

    // ── 운영 ─────────────────────────────────────────────────────────────

    /// @notice 이벤트를 연다. eventId 는 서버 DB 의 prediction_events.id 와 같은 값.
    function openEvent(uint256 eventId, uint16 stockCount, uint256 minStake, uint256 maxStake)
        external
        onlyRole(OPERATOR_ROLE)
    {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.NONE) revert BadState(eventId, e.status);
        if (stockCount == 0 || minStake == 0 || minStake > maxStake) revert BadConfig();
        e.status = Status.OPEN;
        e.stockCount = stockCount;
        e.minStake = minStake;
        e.maxStake = maxStake;
        emit EventOpened(eventId, stockCount, minStake, maxStake);
    }

    /// @notice 접수 마감. 이후 placeBet 은 revert, reveal 이 열린다.
    function seal(uint256 eventId) external onlyRole(OPERATOR_ROLE) {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.OPEN) revert BadState(eventId, e.status);
        e.status = Status.SEALED;
        emit EventSealed(eventId);
    }

    // ── 배팅 (접수 중) ────────────────────────────────────────────────────

    /**
     * @notice 배팅. 방향은 커밋으로만 받는다. ANT 는 사용자 → 이 컨트랙트로 이동한다.
     * 잔액 부족은 토큰의 ERC20InsufficientBalance 로 revert 하고 상태는 남지 않는다(Checks-Effects-Interactions).
     */
    function placeBet(uint256 eventId, address user, uint16 stockIdx, uint256 stake, bytes32 commit)
        external
        onlyRole(OPERATOR_ROLE)
        returns (uint256 betId)
    {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.OPEN) revert BadState(eventId, e.status);
        if (user == address(0)) revert ZeroAddress();
        if (stockIdx >= e.stockCount) revert WrongStock(stockIdx);
        if (stake < e.minStake || stake > e.maxStake) revert StakeOutOfRange(stake, e.minStake, e.maxStake);
        if (hasBet[eventId][user][stockIdx]) revert AlreadyBet(user, stockIdx);

        hasBet[eventId][user][stockIdx] = true;
        betId = _bets[eventId].length;
        _bets[eventId].push(
            Bet({user: user, stockIdx: stockIdx, revealed: false, settled: false, side: Side.UP, stake: stake, commit: commit, payout: 0})
        );
        e.betCount += 1;
        e.escrow += stake;

        token.operatorTransfer(user, address(this), stake);
        emit BetPlaced(eventId, betId, user, stockIdx, stake, commit);
    }

    // ── 리빌 (봉인 뒤) ────────────────────────────────────────────────────

    /**
     * @notice 방향 공개. 커밋과 대조해 맞으면 풀에 더한다. 한 tx 에 여러 건(서버가 100건 단위로 나눈다).
     * 대조 식: keccak256(abi.encodePacked(eventId, stockIdx, uint8(side), noteHash)) — 서버 EventCommits 와 바이트 단위로 같아야 한다.
     */
    function reveal(uint256 eventId, uint256[] calldata betIds, uint8[] calldata sides, bytes32[] calldata noteHashes)
        external
        onlyRole(OPERATOR_ROLE)
    {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.SEALED) revert BadState(eventId, e.status);
        if (betIds.length != sides.length || betIds.length != noteHashes.length) revert LengthMismatch();

        for (uint256 i = 0; i < betIds.length; i++) {
            _revealOne(eventId, betIds[i], sides[i], noteHashes[i]);
        }
    }

    /// reveal 의 한 건. 루프 안 지역변수를 줄이려고 뺐다(solc "stack too deep" — viaIR 을 켜지 않기 위해).
    function _revealOne(uint256 eventId, uint256 betId, uint8 side, bytes32 noteHash) private {
        Bet[] storage bets = _bets[eventId];
        if (betId >= bets.length) revert NoSuchBet(betId);
        Bet storage b = bets[betId];
        if (b.revealed) revert AlreadyRevealed(betId);
        if (side > uint8(Side.DOWN)) revert BadCommit(betId);
        if (keccak256(abi.encodePacked(eventId, b.stockIdx, side, noteHash)) != b.commit) revert BadCommit(betId);

        b.revealed = true;
        b.side = Side(side);
        _pool[eventId][b.stockIdx][side] += b.stake;
        emit BetRevealed(eventId, betId, side);
    }

    /// @notice 리빌 마감. 안 밝혀진 배팅은 이제 풀 밖이다(정산 때 환불). 이 뒤로 reveal 은 revert.
    function revealDone(uint256 eventId) external onlyRole(OPERATOR_ROLE) {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.SEALED) revert BadState(eventId, e.status);
        e.status = Status.REVEALED;
        emit EventRevealed(eventId);
    }

    // ── 판정 ─────────────────────────────────────────────────────────────

    /// @notice 종목 하나 판정. close > base → UP, < → DOWN, = → TIE. 가격은 서버가 넣는 값이라 이벤트로 남겨 누구나 대조하게 한다.
    function resolveStock(uint256 eventId, uint16 stockIdx, uint256 basePrice, uint256 closePrice)
        external
        onlyRole(OPERATOR_ROLE)
    {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.REVEALED) revert BadState(eventId, e.status);
        if (stockIdx >= e.stockCount) revert WrongStock(stockIdx);
        StockResult storage r = _results[eventId][stockIdx];
        if (r.resolved) revert AlreadyResolved(stockIdx);
        if (basePrice == 0 || closePrice == 0) revert BadConfig();

        uint8 winner = closePrice > basePrice ? WINNER_UP : (closePrice < basePrice ? WINNER_DOWN : WINNER_TIE);
        r.resolved = true;
        r.winner = winner;
        r.basePrice = basePrice;
        r.closePrice = closePrice;
        e.resolvedCount += 1;
        emit StockResolved(eventId, stockIdx, basePrice, closePrice, winner);
    }

    /// @notice 전 종목 판정 확인 → RESOLVED. 하나라도 빠지면 revert.
    function resolveDone(uint256 eventId) external onlyRole(OPERATOR_ROLE) {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.REVEALED) revert BadState(eventId, e.status);
        if (e.resolvedCount != e.stockCount) revert NotAllResolved(e.resolvedCount, e.stockCount);
        e.status = Status.RESOLVED;
        emit EventResolved(eventId);
    }

    // ── 정산 ─────────────────────────────────────────────────────────────

    /**
     * @notice 배팅 묶음 정산. 상태를 먼저 바꾸고 송금한다 — 송금이 revert 하면 tx 전체가 되감긴다.
     * payout 0(진 쪽)은 송금 없이 settled 만 찍는다. 환불은 BetRefunded, 나머지는 BetSettled.
     */
    function settle(uint256 eventId, uint256[] calldata betIds) external onlyRole(OPERATOR_ROLE) {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.RESOLVED) revert BadState(eventId, e.status);

        Bet[] storage bets = _bets[eventId];
        for (uint256 i = 0; i < betIds.length; i++) {
            uint256 betId = betIds[i];
            if (betId >= bets.length) revert NoSuchBet(betId);
            Bet storage b = bets[betId];
            if (b.settled) revert AlreadySettled(betId);

            (uint256 payout, bool refund) = _payoutOf(eventId, b);
            b.settled = true;
            b.payout = payout;
            e.settledCount += 1;
            if (payout > 0) {
                e.escrow -= payout;
                token.operatorTransfer(address(this), b.user, payout);
            }
            if (refund) {
                emit BetRefunded(eventId, betId, b.user, payout);
            } else {
                emit BetSettled(eventId, betId, b.user, payout);
            }
        }
    }

    /// @notice 전부 정산된 뒤 잔돈(정수 절삭 나머지)을 treasury 로. 이벤트 종료.
    function sweep(uint256 eventId) external onlyRole(OPERATOR_ROLE) {
        EventInfo storage e = _events[eventId];
        if (e.status != Status.RESOLVED) revert BadState(eventId, e.status);
        if (e.settledCount != e.betCount) revert NotAllSettled(e.settledCount, e.betCount);
        uint256 dust = e.escrow;
        e.escrow = 0;
        e.status = Status.SETTLED;
        if (dust > 0) {
            token.operatorTransfer(address(this), treasury, dust);
        }
        emit EventSettled(eventId, dust);
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    /// 이름이 getEvent 가 아닌 이유: ethers v6 의 Contract 객체가 getEvent(이름) 를 내장 메서드로 갖고 있어 호출이 가려진다.
    function eventInfo(uint256 eventId) external view returns (EventInfo memory) {
        return _events[eventId];
    }

    function getBet(uint256 eventId, uint256 betId) external view returns (Bet memory) {
        if (betId >= _bets[eventId].length) revert NoSuchBet(betId);
        return _bets[eventId][betId];
    }

    function betCount(uint256 eventId) external view returns (uint256) {
        return _bets[eventId].length;
    }

    /// reveal 전엔 둘 다 0 — 접수 중 풀 크기는 서버 집계로만 본다.
    function getPool(uint256 eventId, uint16 stockIdx) external view returns (uint256 up, uint256 down) {
        uint256[2] storage p = _pool[eventId][stockIdx];
        return (p[0], p[1]);
    }

    function getResult(uint256 eventId, uint16 stockIdx) external view returns (StockResult memory) {
        return _results[eventId][stockIdx];
    }

    /// @notice RESOLVED 뒤에만 의미 있다. settle 이 실제로 줄 값과 같은 식.
    function previewPayout(uint256 eventId, uint256 betId) external view returns (uint256 payout, bool refund) {
        if (betId >= _bets[eventId].length) revert NoSuchBet(betId);
        EventInfo storage e = _events[eventId];
        if (e.status != Status.RESOLVED && e.status != Status.SETTLED) revert BadState(eventId, e.status);
        return _payoutOf(eventId, _bets[eventId][betId]);
    }

    // ── 내부 ─────────────────────────────────────────────────────────────

    /// @return payout 줄 금액 @return refund 환불(원금 반환)인지 — 이긴 배당과 구분해 이벤트를 다르게 낸다
    function _payoutOf(uint256 eventId, Bet storage b) private view returns (uint256 payout, bool refund) {
        if (!b.revealed) return (b.stake, true);
        StockResult storage r = _results[eventId][b.stockIdx];
        if (r.winner == WINNER_TIE) return (b.stake, true);
        uint256[2] storage p = _pool[eventId][b.stockIdx];
        uint256 winnerPool = p[r.winner];
        if (winnerPool == 0) return (b.stake, true);
        if (uint8(b.side) != r.winner) return (0, false);
        return (b.stake * (p[0] + p[1]) / winnerPool, false);
    }
}
