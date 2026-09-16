package ssafy.a507.backend.domain.chain.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 배포된 PredictionEventPool(업앤다운 배팅 이벤트 풀) 컨트랙트의 위치와 정산 러너 설정 (ANT-EVENT-02 · 03).
 *
 * <p>{@link PredictTokenProperties} 와 같은 판단이다 — 주소는 배포마다 계산되는 값이라 환경변수로 받고,
 * <b>비어 있어도 앱이 떠야 한다.</b> 풀을 배포하지 않은 팀원의 로컬이 부팅부터 실패하면 이벤트와 무관한 기능까지
 * 개발을 못 한다. 실제로 tx 를 보내는 풀 릴레이어와 이벤트를 읽는 풀 인덱서 ③ 이 호출 직전에 {@link #isDeployed()} 로 막는다.
 *
 * <p>토큰과 마찬가지로 <b>재배포로 바뀌지 않는 것을 전제</b>한다. 진행 중인 이벤트의 배팅금이 이 주소에 살기 때문이다.
 * 회차가 바뀌어도 컨트랙트는 그대로고 {@code eventId} 만 는다(팩토리를 두지 않은 이유, {@code contracts/README.md}).
 *
 * @param settlement 정산 러너(ANT-EVENT-03) 손잡이. yaml 에 없으면 {@link Settlement#DEFAULT}
 */
@ConfigurationProperties(prefix = "app.chain.event-pool")
public record EventPoolProperties(String address, Settlement settlement) {

    /** 0x + 40자 hex. */
    private static final int ADDRESS_LENGTH = 42;

    /**
     * 정산 러너 손잡이 (ANT-EVENT-03).
     *
     * @param chunkSize             reveal · settle 한 tx 에 넣는 배팅 수. Hardhat 실측 settle 100건 ≈ 4.3M 가스 — 이 위로는 tx 하나가 너무 크다
     * @param receiptTimeoutSeconds tx 하나의 채굴을 기다리는 상한. 넘기면 러너가 멈추고 다음 실행이 온체인 상태로 이어 간다
     * @param retryCount            RPC 장애(CHAIN_UNAVAILABLE)일 때만 같은 명령을 다시 보내는 횟수. revert 는 재시도하지 않는다
     * @param retryDelaySeconds     그 재시도 사이 간격
     */
    public record Settlement(int chunkSize, int receiptTimeoutSeconds, int retryCount, int retryDelaySeconds) {
        public static final Settlement DEFAULT = new Settlement(100, 60, 3, 10);

        /** yaml 에 일부만 적혀 0 으로 바인딩된 칸을 기본값으로 메운다. 0 은 어느 칸에도 뜻이 없다. */
        public Settlement effective() {
            return new Settlement(
                    chunkSize > 0 ? chunkSize : DEFAULT.chunkSize,
                    receiptTimeoutSeconds > 0 ? receiptTimeoutSeconds : DEFAULT.receiptTimeoutSeconds,
                    retryCount > 0 ? retryCount : DEFAULT.retryCount,
                    retryDelaySeconds >= 0 ? retryDelaySeconds : DEFAULT.retryDelaySeconds);
        }
    }

    /** 길이까지 보는 이유는 {@link PredictTokenProperties#isDeployed()} 와 같다 — 잘린 주소로 보낸 tx 는 실패도 안 나고 돈도 안 움직인다. */
    public boolean isDeployed() {
        return address != null && address.length() == ADDRESS_LENGTH && address.startsWith("0x");
    }

    /** 소문자로 눕힌 주소. {@code chain_events.contract_address} 와 커서 조회의 키다. */
    public String normalizedAddress() {
        return isDeployed() ? address.toLowerCase() : null;
    }

    /** 정산 설정. 블록이 통째로 없으면(테스트 yaml) 기본값. */
    public Settlement settlementOrDefault() {
        return settlement == null ? Settlement.DEFAULT : settlement.effective();
    }
}
