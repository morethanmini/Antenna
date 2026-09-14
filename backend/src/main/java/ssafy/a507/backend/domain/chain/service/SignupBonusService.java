package ssafy.a507.backend.domain.chain.service;

import java.math.BigInteger;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.config.TokenAmountProperties;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;

/**
 * 가입 보너스 mint (ANT-TOKEN-07). 지갑 연동 직후에 한 번, 빠졌으면 재시도 배치가 다시.
 *
 * <p>왜 연동 시점인가: mint 에는 받을 주소가 필요한데 주소가 생기는 순간이 연동뿐이다. 명세·AC 의 "최초 로그인" 은 그때
 * 주소가 없어 성립하지 않는다.
 *
 * <p>1인 1회는 두 겹이다. ① 연동은 계정당 1회이고 보너스 작업은 지갑 저장과 같은 커밋에서 만든다 — 연동 트랜잭션이
 * 커밋되기 전엔 재시도 배치가 그 회원의 지갑을 못 보므로 둘이 겹칠 창이 없다. ② 대상 조건
 * ({@link OperationRepository#SIGNUP_BONUS_ELIGIBLE})이 진행 중·성공 작업과 원장까지 본다.
 *
 * <p>체인이 꺼져 있거나 전송이 실패해도 연동은 성공한다. 연동은 예측 등록의 전제라 체인 장애를 거기까지 번지게 하지 않는다.
 * 빠진 보너스는 {@link SignupBonusRetryScheduler} 가 받는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(TokenAmountProperties.class)
public class SignupBonusService {

    /** 재시도 한 회에 보내는 최대 인원. 스프링 스케줄러는 단일 스레드라 한 회가 길면 인덱서 회차가 그만큼 밀린다. */
    static final int RETRY_BATCH_SIZE = 20;

    private final TokenOperationService tokenOperations;
    private final OperationRepository operations;
    private final UserRepository users;
    private final TokenAmountProperties amounts;
    private final TransactionTemplate tx;

    /** 지급액. 전송 시점의 금액표를 따른다 — 사용자가 서명한 금액이 아니라 광고처럼 박제할 이유가 없다. */
    public BigInteger amount() {
        return amounts.signupBonus();
    }

    /**
     * 대상이면 보너스 작업(PENDING)을 만든다. <b>호출자의 트랜잭션 안에서</b> 부른다 — 연동은 지갑 저장과 같은 커밋이어야 한다.
     *
     * @return 작업 번호. 체인이 꺼져 있거나 이미 받았으면(진행 중 포함) null
     */
    public String acceptIfEligible(User user) {
        if (!tokenOperations.isEnabled() || !operations.isSignupBonusEligible(user.getId())) {
            return null;
        }
        return operations.save(Operation.accept(user, Operation.Kind.SIGNUP_BONUS, null, null)).getId();
    }

    /**
     * mint 를 보낸다. <b>트랜잭션 밖</b>에서 부른다({@link TokenOperationService} 계약).
     *
     * <p>실패는 던지지 않는다 — 작업은 {@link TokenOperationService} 가 이미 FAILED 로 닫았고 재시도 배치가 다시 집는다.
     * 연동 응답을 실패로 만들 이유가 없다.
     *
     * @return 보냈으면 true
     */
    public boolean send(String operationId) {
        try {
            tokenOperations.dispatchMint(operationId, amount(), TokenReason.SIGNUP_BONUS);
            return true;
        } catch (IllegalStateException e) {
            // 트랜잭션 안에서 불렀거나 이미 보낸 작업을 다시 보냈다 — 호출 코드의 버그다. 삼키면 못 찾는다.
            throw e;
        } catch (RuntimeException e) {
            log.warn("가입 보너스 전송 실패 — 작업 {} 은 FAILED, 재시도 배치가 다시 보낸다: {}", operationId, e.getMessage());
            return false;
        }
    }

    /**
     * 재시도 한 회. 첫 실패에서 멈춘다 — RPC 장애면 나머지도 같은 이유로 실패하고 FAILED 작업만 쌓인다.
     *
     * @return mint 를 보낸 인원
     */
    public int retryOnce() {
        if (!tokenOperations.isEnabled()) {
            return 0;
        }
        List<Long> candidates = tx.execute(s -> operations.signupBonusCandidates(RETRY_BATCH_SIZE));
        int sent = 0;
        for (Long userId : candidates) {
            // 목록을 뽑은 뒤 인덱서가 늦게 온 Minted 를 원장에 썼을 수 있다 — 접수 트랜잭션에서 조건을 다시 본다.
            String operationId = tx.execute(s -> users.findById(userId).map(this::acceptIfEligible).orElse(null));
            if (operationId == null) {
                continue;
            }
            if (!send(operationId)) {
                break;
            }
            sent++;
        }
        return sent;
    }
}
