package ssafy.a507.backend.domain.chain.service;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.relay.TokenRelayer;
import ssafy.a507.backend.domain.chain.relay.TokenRevertException;

/**
 * 체인 없이 {@link TokenOperationService} 의 상태 전이를 검증하기 위한 가짜 릴레이어 — {@code FakeAnchorRelayer} 와 같은 꼴.
 *
 * <p>전송 호출마다 미리 심어 둔 대본을 하나씩 꺼내 실행한다(tx 해시 반환 / 잔액 부족 / RPC 장애 / 컨트랙트 거부).
 * 대본이 비면 tx 해시를 돌려준다. 받은 인자는 전부 기록해 "누구 지갑으로 얼마를" 이 맞는지 본다.
 */
public class FakeTokenRelayer implements TokenRelayer {

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        FakeTokenRelayer fakeTokenRelayer() {
            return new FakeTokenRelayer();
        }
    }

    public record Call(String kind, String from, String to, BigInteger amount, TokenReason reason) {}

    private boolean enabled = true;
    /** {@code balanceOf} 가 돌려줄 값. 이벤트 묶음 등록(ANT-EVENT-05)의 잔액 검사가 체인 잔액을 본다. */
    private BigInteger balance = BigInteger.ZERO;
    private final Deque<Supplier<String>> script = new ArrayDeque<>();
    private final List<Call> calls = new ArrayList<>();
    private int txSeq = 0;

    public void reset() {
        enabled = true;
        balance = BigInteger.ZERO;
        script.clear();
        calls.clear();
        txSeq = 0;
    }

    public void disable() {
        enabled = false;
    }

    /** 체인 잔액을 심는다. 원장(token_ledger)과 달리 인덱서 지연이 없는 값이다. */
    public FakeTokenRelayer withBalance(BigInteger amount) {
        this.balance = amount;
        return this;
    }

    public FakeTokenRelayer thenSent() {
        script.add(this::nextTxHash);
        return this;
    }

    public FakeTokenRelayer thenInsufficientBalance() {
        script.add(() -> { throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE); });
        return this;
    }

    public FakeTokenRelayer thenUnavailable() {
        script.add(() -> { throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE); });
        return this;
    }

    public FakeTokenRelayer thenRevert(String errorName) {
        script.add(() -> { throw new TokenRevertException(errorName); });
        return this;
    }

    /** 릴레이어가 BusinessException·TokenRevertException 으로 못 바꾼 예외(라이브러리 내부 오류 등). */
    public FakeTokenRelayer thenThrow(RuntimeException e) {
        script.add(() -> { throw e; });
        return this;
    }

    public List<Call> calls() {
        return calls;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public String mint(String to, BigInteger amount, TokenReason reason) {
        calls.add(new Call("mint", null, to, amount, reason));
        return next();
    }

    @Override
    public String burn(String from, BigInteger amount, TokenReason reason) {
        calls.add(new Call("burn", from, null, amount, reason));
        return next();
    }

    @Override
    public String subscribe(String subscriber, String creator, BigInteger amount) {
        calls.add(new Call("subscribe", subscriber, creator, amount, null));
        return next();
    }

    @Override
    public BigInteger balanceOf(String address) {
        return balance;
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
        return "0x" + String.format("%064x", txSeq);
    }
}
