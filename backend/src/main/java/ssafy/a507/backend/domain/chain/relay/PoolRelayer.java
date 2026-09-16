package ssafy.a507.backend.domain.chain.relay;

import java.math.BigInteger;
import java.util.List;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * PredictionEventPool(업앤다운 배팅 이벤트 풀) 호출기 (ANT-EVENT-02).
 *
 * <p>인터페이스로 뺀 이유는 {@link TokenRelayer} 와 같다 — REST 의 묶음 등록 서비스와 정산 러너(ANT-EVENT-03)의 상태 전이는
 * 체인 없이 검증돼야 하고, 그러려면 "보냈다 / 잔액 부족 / 컨트랙트가 거부" 를 마음대로 내는 가짜가 필요하다.
 *
 * <p>금액은 <b>정수 ANT</b>(decimals 0). 가격은 <b>×100 정수</b>({@code numeric(14,2)} 의 소수점을 지운 값) — 컨트랙트는
 * 크기 비교만 하므로 base·close 의 단위가 같기만 하면 된다. 주소는 {@code users.wallet_address} 형식(0x + 40 hex).
 *
 * <p>상태 변경 메서드는 전부 <b>receipt 를 기다리지 않고</b> tx 해시를 돌려준다. 확정은 인덱서 ③ 이 이벤트로 본다.
 * 정산 러너처럼 다음 단계 전에 채굴을 확인해야 하면 호출자가 {@code ChainLogSource.receiptStatus} 로 기다린다 —
 * 릴레이어 안에서 기다리면 요청 스레드의 {@code placeBet} 까지 블록 하나(10초)를 잡게 된다.
 *
 * <p>공통 예외:
 * <ul>
 *   <li>{@link BusinessException} CHAIN_UNAVAILABLE — 릴레이어 꺼짐 · RPC 장애 · 노드 거부 · 키에 OPERATOR_ROLE 없음</li>
 *   <li>{@link BusinessException} INSUFFICIENT_BALANCE — {@code placeBet} 시뮬레이션에서 {@code ERC20InsufficientBalance}</li>
 *   <li>{@link PoolRevertException} — 그 밖의 컨트랙트 거부({@code BadState · AlreadyBet · BadCommit} 등). 호출자 검증을 뚫은 서버 버그</li>
 *   <li>{@link IllegalArgumentException} — 인자 형식(주소 · 금액 0 · 인덱스 범위 · 배열 길이). 호출자 버그</li>
 * </ul>
 */
public interface PoolRelayer {

    /** RPC·키·풀 주소가 갖춰져 tx 를 보낼 수 있는지. 아니면 모든 메서드는 CHAIN_UNAVAILABLE 을 던진다. */
    boolean isEnabled();

    // ── 상태 변경 (전부 OPERATOR_ROLE, receipt 안 기다림) ────────────────

    /** {@code openEvent} — NONE → OPEN. eventId 는 {@code prediction_events.id}. @return tx 해시 */
    String openEvent(long eventId, int stockCount, BigInteger minStake, BigInteger maxStake);

    /**
     * {@code placeBet} — 사용자 ANT 가 풀로 옮겨진다(잔액 부족은 INSUFFICIENT_BALANCE). 트랜잭션 밖에서 부른다.
     *
     * @param commit {@code EventCommits.commit(...)} 32바이트
     * @return tx 해시. {@code Operation.markSent} 에 넣어 인덱서가 찾게 한다
     */
    String placeBet(long eventId, String user, int stockIdx, BigInteger stake, byte[] commit);

    /** {@code seal} — OPEN → SEALED. 이후 placeBet 은 revert. */
    String seal(long eventId);

    /** {@code reveal} — 묶음 리빌. 세 목록은 같은 길이·같은 순서. noteHash 는 0x + 64 hex. */
    String reveal(long eventId, List<Long> betIds, List<Prediction.Direction> sides, List<String> noteHashes);

    /** {@code revealDone} — SEALED → REVEALED. 안 밝혀진 배팅은 이제 풀 밖. */
    String revealDone(long eventId);

    /** {@code resolveStock} — 종목 하나 판정. 가격은 ×100 정수, 둘 다 0 이면 BadConfig. */
    String resolveStock(long eventId, int stockIdx, BigInteger basePrice, BigInteger closePrice);

    /** {@code resolveDone} — 전 종목 판정 확인 → RESOLVED. */
    String resolveDone(long eventId);

    /** {@code settle} — 묶음 정산·송금. */
    String settle(long eventId, List<Long> betIds);

    /** {@code sweep} — 잔돈 → treasury, SETTLED. */
    String sweep(long eventId);

    // ── 조회 (eth_call, tx 없음) — 정산 러너가 멱등 판단에 쓴다 ────────────

    EventInfo eventInfo(long eventId);

    /** 없는 betId 면 {@link PoolRevertException} NoSuchBet. */
    Bet bet(long eventId, long betId);

    StockResult result(long eventId, int stockIdx);

    /** reveal 전엔 둘 다 0. */
    Pool pool(long eventId, int stockIdx);

    long betCount(long eventId);

    /** 컨트랙트 {@code Status}. ordinal 이 곧 온체인 값이라 순서를 바꾸면 안 된다. */
    enum Status {
        NONE,
        OPEN,
        SEALED,
        REVEALED,
        RESOLVED,
        SETTLED
    }

    /** {@code eventInfo(eventId)}. */
    record EventInfo(
            Status status,
            int stockCount,
            int resolvedCount,
            BigInteger minStake,
            BigInteger maxStake,
            long betCount,
            long settledCount,
            BigInteger escrow) {}

    /** {@code getBet(eventId, betId)}. side 는 revealed 전엔 의미 없다(컨트랙트 기본값 UP). */
    record Bet(
            String user,
            int stockIdx,
            boolean revealed,
            boolean settled,
            Prediction.Direction side,
            BigInteger stake,
            String commitHex,
            BigInteger payout) {}

    /** {@code getResult(eventId, stockIdx)}. resolved 전엔 winner 가 UP(0) 으로 보이니 resolved 를 먼저 본다. */
    record StockResult(boolean resolved, PredictionEventStock.Winner winner, BigInteger basePrice, BigInteger closePrice) {}

    /** {@code getPool(eventId, stockIdx)} — [UP, DOWN]. */
    record Pool(BigInteger up, BigInteger down) {}
}
