package ssafy.a507.backend.domain.event.relay;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.chain.relay.PoolRevertException;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 체인 없이 묶음 등록·봉인·재전송의 상태 전이를 검증하기 위한 가짜 풀 릴레이어 — {@code FakeTokenRelayer} 와 같은 꼴.
 *
 * <p>전송마다 미리 심어 둔 대본을 하나씩 꺼낸다(tx 해시 / 잔액 부족 / RPC 장애 / 컨트랙트 거부). 대본이 비면 tx 해시를
 * 돌려준다. 인자는 전부 기록한다 — "어느 종목에 얼마를, 어떤 커밋으로" 가 맞는지 보는 것이 이 fake 의 존재 이유다.
 *
 * <p>조회 메서드는 정산 러너(ANT-EVENT-03) 몫이라 여기서는 쓰지 않는다. 불러도 되는 값을 지어내면 그걸 믿는 테스트가
 * 생기므로 {@link UnsupportedOperationException} 으로 막는다.
 */
public class FakePoolRelayer implements PoolRelayer {

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakePoolRelayer fakePoolRelayer() {
            return new FakePoolRelayer();
        }
    }

    /** {@code commit} 은 hex 로 눕혀 기록한다 — 테스트가 DB 의 {@code event_commit} 과 그대로 대조한다. */
    public record Call(String kind, long eventId, String wallet, int stockIdx, BigInteger stake, String commitHex) {}

    private boolean enabled = true;
    private final Deque<Supplier<String>> script = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private int txSeq = 0;

    public void reset() {
        enabled = true;
        script.clear();
        calls.clear();
        txSeq = 0;
    }

    public void disable() {
        enabled = false;
    }

    public FakePoolRelayer thenSent() {
        script.add(this::nextTxHash);
        return this;
    }

    public FakePoolRelayer thenInsufficientBalance() {
        script.add(() -> { throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE); });
        return this;
    }

    public FakePoolRelayer thenUnavailable() {
        script.add(() -> { throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE); });
        return this;
    }

    public FakePoolRelayer thenRevert(String errorName) {
        script.add(() -> { throw new PoolRevertException(errorName); });
        return this;
    }

    public List<Call> calls() {
        return calls;
    }

    public List<Call> callsOf(String kind) {
        return calls.stream().filter(c -> c.kind().equals(kind)).toList();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String openEvent(long eventId, int stockCount, BigInteger minStake, BigInteger maxStake) {
        calls.add(new Call("openEvent", eventId, null, stockCount, maxStake, null));
        return next();
    }

    @Override
    public String placeBet(long eventId, String user, int stockIdx, BigInteger stake, byte[] commit) {
        calls.add(new Call("placeBet", eventId, user, stockIdx, stake, Numeric.toHexString(commit)));
        return next();
    }

    @Override
    public String seal(long eventId) {
        calls.add(new Call("seal", eventId, null, -1, null, null));
        return next();
    }

    @Override
    public String reveal(long eventId, List<Long> betIds, List<Prediction.Direction> sides, List<String> noteHashes) {
        throw new UnsupportedOperationException("reveal 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public String revealDone(long eventId) {
        throw new UnsupportedOperationException("revealDone 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public String resolveStock(long eventId, int stockIdx, BigInteger basePrice, BigInteger closePrice) {
        throw new UnsupportedOperationException("resolveStock 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public String resolveDone(long eventId) {
        throw new UnsupportedOperationException("resolveDone 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public String settle(long eventId, List<Long> betIds) {
        throw new UnsupportedOperationException("settle 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public String sweep(long eventId) {
        throw new UnsupportedOperationException("sweep 은 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public EventInfo eventInfo(long eventId) {
        throw new UnsupportedOperationException("조회는 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public Bet bet(long eventId, long betId) {
        throw new UnsupportedOperationException("조회는 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public StockResult result(long eventId, int stockIdx) {
        throw new UnsupportedOperationException("조회는 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public Pool pool(long eventId, int stockIdx) {
        throw new UnsupportedOperationException("조회는 정산 러너(ANT-EVENT-03) 몫이다");
    }

    @Override
    public long betCount(long eventId) {
        throw new UnsupportedOperationException("조회는 정산 러너(ANT-EVENT-03) 몫이다");
    }

    private String next() {
        if (!enabled) {
            throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
        }
        Supplier<String> step = script.poll();
        return step == null ? nextTxHash() : step.get();
    }

    private String nextTxHash() {
        txSeq++;
        return "0x" + String.format("%064x", 0xB0 + txSeq);
    }
}
