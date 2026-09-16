package ssafy.a507.backend.domain.chain.service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.commit.EventCommits;
import ssafy.a507.backend.domain.chain.indexer.FakeChainLogSource;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.chain.relay.PoolRevertException;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 체인 없이 정산 러너를 검증하기 위한 가짜 풀 (ANT-EVENT-03).
 *
 * <p>{@code PredictionEventPool.sol} 의 상태 머신·배분식을 메모리에 그대로 옮겼다 — 순서가 틀리면 {@link PoolRevertException}
 * {@code BadState}, 이중 리빌은 {@code AlreadyRevealed}, 이중 정산은 {@code AlreadySettled}. 러너가 "온체인 상태로 건너뛰기" 를
 * 제대로 하는지는 이 거부들이 판정한다. 배분식은 컨트랙트와 같다: 이긴 쪽 {@code stake × (up + down) / winnerPool}, 진 쪽 0,
 * 미리빌·TIE·이긴 풀 0 → 원금.
 *
 * <p>보낸 tx 의 receipt 는 {@link FakeChainLogSource} 에 바로 심는다(기본 성공). 장애 주입: {@link #unavailableNext(int)}(RPC 장애),
 * {@link #noReceiptNext()}(채굴 안 됨 → timeout), {@link #revertReceiptNext()}(채굴에서 revert).
 */
public class FakePoolRelayer implements PoolRelayer {

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakePoolRelayer fakePoolRelayer(FakeChainLogSource source) {
            return new FakePoolRelayer(source);
        }
    }

    static final class EventState {
        Status status = Status.NONE;
        int stockCount;
        BigInteger minStake;
        BigInteger maxStake;
        BigInteger escrow = BigInteger.ZERO;
        final List<BetState> bets = new ArrayList<>();
        final Map<Integer, BigInteger[]> pools = new HashMap<>();
        final Map<Integer, StockResult> results = new HashMap<>();
        long settledCount;
    }

    static final class BetState {
        String user;
        int stockIdx;
        boolean revealed;
        boolean settled;
        Prediction.Direction side = Prediction.Direction.UP;
        BigInteger stake;
        String commit;
        BigInteger payout = BigInteger.ZERO;
    }

    private final FakeChainLogSource source;
    private final Map<Long, EventState> events = new HashMap<>();
    private final List<String> sent = new ArrayList<>();
    private int txSeq = 0;
    private int unavailableNext = 0;
    /** {@link #unavailableNextAfter} — 이만큼 성공한 뒤부터 장애. */
    private int unavailableAfter = -1;
    private boolean noReceiptNext = false;
    private boolean revertReceiptNext = false;
    private boolean enabled = true;

    public FakePoolRelayer(FakeChainLogSource source) {
        this.source = source;
    }

    public void reset() {
        events.clear();
        sent.clear();
        txSeq = 0;
        unavailableNext = 0;
        unavailableAfter = -1;
        noReceiptNext = false;
        revertReceiptNext = false;
        enabled = true;
    }

    /** 보낸 명령 목록("reveal" · "settle" …). 러너가 어느 단계를 건너뛰었는지 세는 재료. */
    public List<String> sent() {
        return sent;
    }

    public void enabled(boolean value) {
        enabled = value;
    }

    /** 다음 n 번의 전송이 CHAIN_UNAVAILABLE 로 실패한다(상태는 안 바뀐다). */
    public void unavailableNext(int n) {
        unavailableNext = n;
    }

    /** 앞으로 {@code after} 번은 성공하고 그 다음 {@code n} 번이 장애 — 특정 단계에서 멈추게 할 때. */
    public void unavailableNextAfter(int after, int n) {
        unavailableAfter = after;
        unavailableNext = n;
    }

    /** 다음 전송의 receipt 를 심지 않는다 — 러너는 timeout 까지 기다린다. 상태는 바뀐다(채굴은 뒤늦게 된 셈). */
    public void noReceiptNext() {
        noReceiptNext = true;
    }

    /** 다음 전송의 receipt 가 실패(0)다. 상태는 안 바뀐다. */
    public void revertReceiptNext() {
        revertReceiptNext = true;
    }

    public EventState state(long eventId) {
        return events.computeIfAbsent(eventId, k -> new EventState());
    }

    // ── 상태 변경 ────────────────────────────────────────────────────────

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String openEvent(long eventId, int stockCount, BigInteger minStake, BigInteger maxStake) {
        EventState e = state(eventId);
        require(e.status == Status.NONE, "BadState");
        return send("openEvent", () -> {
            e.status = Status.OPEN;
            e.stockCount = stockCount;
            e.minStake = minStake;
            e.maxStake = maxStake;
        });
    }

    @Override
    public String placeBet(long eventId, String user, int stockIdx, BigInteger stake, byte[] commit) {
        EventState e = state(eventId);
        require(e.status == Status.OPEN, "BadState");
        require(stockIdx < e.stockCount, "WrongStock");
        require(stake.compareTo(e.minStake) >= 0 && stake.compareTo(e.maxStake) <= 0, "StakeOutOfRange");
        return send("placeBet", () -> {
            BetState b = new BetState();
            b.user = user;
            b.stockIdx = stockIdx;
            b.stake = stake;
            b.commit = org.web3j.utils.Numeric.toHexString(commit);
            e.bets.add(b);
            e.escrow = e.escrow.add(stake);
        });
    }

    /** placeBet 을 거치지 않고 배팅을 심는다 — 테스트 픽스처용. @return betId */
    public long seedBet(long eventId, String user, int stockIdx, BigInteger stake) {
        EventState e = state(eventId);
        BetState b = new BetState();
        b.user = user;
        b.stockIdx = stockIdx;
        b.stake = stake;
        e.bets.add(b);
        e.escrow = e.escrow.add(stake);
        return e.bets.size() - 1;
    }

    @Override
    public String seal(long eventId) {
        EventState e = state(eventId);
        require(e.status == Status.OPEN, "BadState");
        return send("seal", () -> e.status = Status.SEALED);
    }

    @Override
    public String reveal(long eventId, List<Long> betIds, List<Prediction.Direction> sides, List<String> noteHashes) {
        EventState e = state(eventId);
        require(e.status == Status.SEALED, "BadState");
        require(betIds.size() == sides.size() && betIds.size() == noteHashes.size(), "LengthMismatch");
        for (Long id : betIds) {
            require(id < e.bets.size(), "NoSuchBet");
            require(!e.bets.get(id.intValue()).revealed, "AlreadyRevealed");
        }
        return send("reveal", () -> {
            for (int i = 0; i < betIds.size(); i++) {
                BetState b = e.bets.get(betIds.get(i).intValue());
                b.revealed = true;
                b.side = sides.get(i);
                BigInteger[] pool = e.pools.computeIfAbsent(b.stockIdx, k -> new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
                int s = sides.get(i) == Prediction.Direction.UP ? 0 : 1;
                pool[s] = pool[s].add(b.stake);
            }
        });
    }

    @Override
    public String revealDone(long eventId) {
        EventState e = state(eventId);
        require(e.status == Status.SEALED, "BadState");
        return send("revealDone", () -> e.status = Status.REVEALED);
    }

    @Override
    public String resolveStock(long eventId, int stockIdx, BigInteger basePrice, BigInteger closePrice) {
        EventState e = state(eventId);
        require(e.status == Status.REVEALED, "BadState");
        require(stockIdx < e.stockCount, "WrongStock");
        require(!e.results.containsKey(stockIdx), "AlreadyResolved");
        return send("resolveStock", () -> {
            int cmp = closePrice.compareTo(basePrice);
            PredictionEventStock.Winner w =
                    cmp > 0 ? PredictionEventStock.Winner.UP : cmp < 0 ? PredictionEventStock.Winner.DOWN : PredictionEventStock.Winner.TIE;
            e.results.put(stockIdx, new StockResult(true, w, basePrice, closePrice));
        });
    }

    @Override
    public String resolveDone(long eventId) {
        EventState e = state(eventId);
        require(e.status == Status.REVEALED, "BadState");
        require(e.results.size() == e.stockCount, "NotAllResolved");
        return send("resolveDone", () -> e.status = Status.RESOLVED);
    }

    @Override
    public String settle(long eventId, List<Long> betIds) {
        EventState e = state(eventId);
        require(e.status == Status.RESOLVED, "BadState");
        for (Long id : betIds) {
            require(id < e.bets.size(), "NoSuchBet");
            require(!e.bets.get(id.intValue()).settled, "AlreadySettled");
        }
        return send("settle", () -> {
            for (Long id : betIds) {
                BetState b = e.bets.get(id.intValue());
                b.settled = true;
                b.payout = payoutOf(e, b);
                e.settledCount++;
                e.escrow = e.escrow.subtract(b.payout);
            }
        });
    }

    @Override
    public String sweep(long eventId) {
        EventState e = state(eventId);
        require(e.status == Status.RESOLVED, "BadState");
        require(e.settledCount == e.bets.size(), "NotAllSettled");
        return send("sweep", () -> {
            e.escrow = BigInteger.ZERO;
            e.status = Status.SETTLED;
        });
    }

    // ── 조회 ─────────────────────────────────────────────────────────────

    @Override
    public EventInfo eventInfo(long eventId) {
        EventState e = state(eventId);
        return new EventInfo(e.status, e.stockCount, e.results.size(), e.minStake, e.maxStake, e.bets.size(), e.settledCount, e.escrow);
    }

    @Override
    public Bet bet(long eventId, long betId) {
        EventState e = state(eventId);
        require(betId < e.bets.size(), "NoSuchBet");
        BetState b = e.bets.get((int) betId);
        return new Bet(b.user, b.stockIdx, b.revealed, b.settled, b.side, b.stake, b.commit, b.payout);
    }

    @Override
    public StockResult result(long eventId, int stockIdx) {
        return state(eventId).results.getOrDefault(stockIdx, new StockResult(false, PredictionEventStock.Winner.UP, BigInteger.ZERO, BigInteger.ZERO));
    }

    @Override
    public Pool pool(long eventId, int stockIdx) {
        BigInteger[] p = state(eventId).pools.getOrDefault(stockIdx, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
        return new Pool(p[0], p[1]);
    }

    @Override
    public long betCount(long eventId) {
        return state(eventId).bets.size();
    }

    /** 전 배팅의 payout 합 — 검산용. */
    public BigInteger totalPayout(long eventId) {
        BigInteger sum = BigInteger.ZERO;
        for (BetState b : state(eventId).bets) {
            sum = sum.add(b.payout);
        }
        return sum;
    }

    // ── 내부 ────────────────────────────────────────────────────────────

    private static BigInteger payoutOf(EventState e, BetState b) {
        if (!b.revealed) {
            return b.stake;
        }
        StockResult r = e.results.get(b.stockIdx);
        if (r.winner() == PredictionEventStock.Winner.TIE) {
            return b.stake;
        }
        BigInteger[] p = e.pools.getOrDefault(b.stockIdx, new BigInteger[] {BigInteger.ZERO, BigInteger.ZERO});
        BigInteger winnerPool = p[r.winner().chainValue()];
        if (winnerPool.signum() == 0) {
            return b.stake;
        }
        if (EventCommits.sideOf(b.side) != r.winner().chainValue()) {
            return BigInteger.ZERO;
        }
        return b.stake.multiply(p[0].add(p[1])).divide(winnerPool);
    }

    private static void require(boolean ok, String error) {
        if (!ok) {
            throw new PoolRevertException(error);
        }
    }

    /** 시뮬레이션(위 require)을 지난 명령의 전송. 장애 주입은 여기서. */
    private String send(String what, Runnable apply) {
        if (!enabled) {
            throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
        }
        if (unavailableAfter > 0) {
            unavailableAfter--;
        } else if (unavailableNext > 0) {
            unavailableNext--;
            throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
        }
        String tx = "0x" + String.format("%064x", ++txSeq);
        sent.add(what);
        if (revertReceiptNext) {
            revertReceiptNext = false;
            source.receipt(tx, false);
            return tx;
        }
        apply.run();
        if (noReceiptNext) {
            noReceiptNext = false;
            return tx;
        }
        source.receipt(tx, true);
        return tx;
    }
}
