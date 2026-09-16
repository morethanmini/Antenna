package ssafy.a507.backend.domain.chain.relay;

import lombok.Getter;

/**
 * PredictionEventPool 이 호출을 거부했다 (ANT-EVENT-02).
 *
 * <p>{@link TokenRevertException} 과 같은 자리다. {@code ERC20InsufficientBalance}(→ INSUFFICIENT_BALANCE 409) 와
 * {@code AccessControlUnauthorizedAccount}(→ CHAIN_UNAVAILABLE 503) 는 여기로 오지 않는다. 여기 오는 건
 * {@code BadState · WrongStock · StakeOutOfRange · AlreadyBet · BadCommit · NotAllRevealed …} 처럼 <b>서버 쪽 검증이나
 * 러너 순서가 틀린 경우</b>다. 다시 보내도 같으므로 진입점은 FAILED 로 기록하고 500 을 낸다.
 *
 * <p>정산 러너(ANT-EVENT-03)는 {@code BadState} 를 "이미 지난 단계" 신호로 쓰지 않는다 — 시작할 때 {@code eventInfo} 를
 * 읽어 단계를 건너뛰고, 그래도 여기 오면 진짜 버그다.
 */
@Getter
public class PoolRevertException extends RuntimeException {

    /** 컨트랙트 custom error 이름. 셀렉터를 못 알아보면 "Unknown(0x…)" */
    private final String errorName;

    public PoolRevertException(String errorName) {
        super("PredictionEventPool revert: " + errorName);
        this.errorName = errorName;
    }
}
