package ssafy.a507.backend.domain.chain.indexer;

import java.math.BigInteger;
import java.util.Optional;

/**
 * 디코딩된 PredictionEventPool 이벤트 한 건 (ANT-EVENT-02). 열 가지를 한 모양으로 담는다.
 *
 * <p>{@link TokenLog} 와 같은 이유로 web3j 의 {@code Log} 를 들고 다니지 않는다 — 가짜 소스로 테스트하려면 RPC 타입에서
 * 떼어야 하고, hex 정규화(소문자·0x)는 디코더 한 곳에서 끝낸다. 이벤트마다 쓰는 칸이 다르다 — 안 쓰는 칸은 null.
 *
 * @param eventId    전부. 온체인 eventId = {@code prediction_events.id}
 * @param betId      BetPlaced · BetRevealed · BetSettled · BetRefunded
 * @param user       BetPlaced · BetSettled · BetRefunded — 지갑 주소(소문자)
 * @param stockIdx   BetPlaced · StockResolved
 * @param amount     BetPlaced 의 stake · BetSettled 의 payout · BetRefunded 의 stake · EventSettled 의 dust
 * @param commitHex  BetPlaced 의 commit(0x + 64 hex)
 * @param side       BetRevealed — 0 UP · 1 DOWN
 * @param basePrice  StockResolved — ×100 정수
 * @param closePrice StockResolved — ×100 정수
 * @param winner     StockResolved — 0 UP · 1 DOWN · 2 TIE
 * @param stockCount EventOpened
 * @param minStake   EventOpened
 * @param maxStake   EventOpened
 */
public record PoolLog(
        String txHash,
        int logIndex,
        long blockNumber,
        String blockHash,
        String contractAddress,
        Kind kind,
        long eventId,
        Long betId,
        String user,
        Integer stockIdx,
        BigInteger amount,
        String commitHex,
        Integer side,
        BigInteger basePrice,
        BigInteger closePrice,
        Integer winner,
        Integer stockCount,
        BigInteger minStake,
        BigInteger maxStake) {

    public enum Kind {
        EVENT_OPENED("EventOpened"),
        BET_PLACED("BetPlaced"),
        EVENT_SEALED("EventSealed"),
        BET_REVEALED("BetRevealed"),
        EVENT_REVEALED("EventRevealed"),
        STOCK_RESOLVED("StockResolved"),
        EVENT_RESOLVED("EventResolved"),
        BET_SETTLED("BetSettled"),
        BET_REFUNDED("BetRefunded"),
        EVENT_SETTLED("EventSettled");

        /** {@code chain_events.event_name} 에 들어가는 이름 — 컨트랙트 이벤트 이름 그대로. 토큰·앵커 이벤트와 겹치지 않는다. */
        public final String eventName;

        Kind(String eventName) {
            this.eventName = eventName;
        }

        public static Optional<Kind> ofEventName(String name) {
            for (Kind k : values()) {
                if (k.eventName.equals(name)) {
                    return Optional.of(k);
                }
            }
            return Optional.empty();
        }
    }
}
