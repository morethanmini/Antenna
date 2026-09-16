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
 * 묶음 등록·봉인·재전송의 <b>전송 실패 처리</b>를 검증하기 위한 대본식 가짜 릴레이어 — {@code FakeTokenRelayer} 와 같은 꼴.
 *
 * <p>전송마다 미리 심어 둔 대본을 하나씩 꺼낸다(tx 해시 / 잔액 부족 / RPC 장애 / 컨트랙트 거부). 대본이 비면 tx 해시를
 * 돌려준다. 인자는 전부 기록한다 — "어느 종목에 얼마를, 어떤 커밋으로" 가 맞는지 보는 것이 이 가짜의 존재 이유다.
 *
 * <p><b>{@code chain/service/FakePoolRelayer}(ANT-EVENT-03)와 다른 도구다.</b> 그쪽은 컨트랙트 상태 머신을 메모리에 옮겨
 * "러너가 끝난 단계를 건너뛰는가" 를 판정한다. 이쪽은 상태 머신이 없는 대신 <b>실패를 마음대로 주입</b>해 "작업 행이
 * 어떻게 닫히는가" 를 본다 — 잔액 부족 주입은 그쪽에 없다. 이름을 나눠 둔 이유는 둘의 {@code @TestConfiguration} 이
 * 같은 이름의 {@code @Primary} 빈을 내면 한 테스트가 둘을 {@code @Import} 하는 순간 컨텍스트가 뜨지 않기 때문이다.
 *
 * <p>조회 메서드는 정산 러너(ANT-EVENT-03) 몫이라 여기서는 쓰지 않는다. 불러도 되는 값을 지어내면 그걸 믿는 테스트가
 * 생기므로 {@link UnsupportedOperationException} 으로 막는다.
 */
public class ScriptedPoolRelayer implements PoolRelayer {

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        ScriptedPoolRelayer scriptedPoolRelayer() {
            return new ScriptedPoolRelayer();
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

    public ScriptedPoolRelayer thenSent() {
        script.add(this::nextTxHash);
        return this;
    }

    public ScriptedPoolRelayer thenInsufficientBalance() {
        script.add(() -> { throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE); });
        return this;
    }

    public ScriptedPoolRelayer thenUnavailable() {
        script.add(() -> { throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE); });
        return this;
    }

    public ScriptedPoolRelayer thenRevert(String errorName) {
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
