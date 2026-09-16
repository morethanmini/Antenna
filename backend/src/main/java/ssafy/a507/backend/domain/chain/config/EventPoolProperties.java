package ssafy.a507.backend.domain.chain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 배포된 PredictionEventPool(업앤다운 배팅 이벤트 풀) 컨트랙트의 위치 (ANT-EVENT-02).
 *
 * <p>{@link PredictTokenProperties} 와 같은 판단이다 — 주소는 배포마다 계산되는 값이라 환경변수로 받고,
 * <b>비어 있어도 앱이 떠야 한다.</b> 풀을 배포하지 않은 팀원의 로컬이 부팅부터 실패하면 이벤트와 무관한 기능까지
 * 개발을 못 한다. 실제로 tx 를 보내는 풀 릴레이어와 이벤트를 읽는 풀 인덱서 ③ 이 호출 직전에 {@link #isDeployed()} 로 막는다.
 *
 * <p>토큰과 마찬가지로 <b>재배포로 바뀌지 않는 것을 전제</b>한다. 진행 중인 이벤트의 배팅금이 이 주소에 살기 때문이다.
 * 회차가 바뀌어도 컨트랙트는 그대로고 {@code eventId} 만 는다(팩토리를 두지 않은 이유, {@code contracts/README.md}).
 */
@ConfigurationProperties(prefix = "app.chain.event-pool")
public record EventPoolProperties(String address) {

    /** 0x + 40자 hex. */
    private static final int ADDRESS_LENGTH = 42;

    /** 길이까지 보는 이유는 {@link PredictTokenProperties#isDeployed()} 와 같다 — 잘린 주소로 보낸 tx 는 실패도 안 나고 돈도 안 움직인다. */
    public boolean isDeployed() {
        return address != null && address.length() == ADDRESS_LENGTH && address.startsWith("0x");
    }

    /** 소문자로 눕힌 주소. {@code chain_events.contract_address} 와 커서 조회의 키다. */
    public String normalizedAddress() {
        return isDeployed() ? address.toLowerCase() : null;
    }
}
