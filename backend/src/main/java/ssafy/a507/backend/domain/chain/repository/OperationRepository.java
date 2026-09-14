package ssafy.a507.backend.domain.chain.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;

public interface OperationRepository extends JpaRepository<Operation, String> {

    /**
     * 가입 보너스를 받아야 하는 회원 (ANT-TOKEN-07). 연동 직후와 재시도 배치가 이 조건 하나를 같이 쓴다 — 빠지는 경우
     * (전송 실패 · 체인 꺼진 채 연동 · 기능 배포 전 연동)가 전부 여기로 모인다.
     *
     * <p><b>원장 조건이 이중 지급을 막는다.</b> SEND_LOST 로 닫힌 작업의 tx 가 실제로는 나갔다면 인덱서가 이미 Minted 를
     * 원장에 썼다(SEND_LOST 판정은 10분 뒤, 블록은 10초). 작업 상태만 보면 FAILED 라 다시 보내게 된다.
     */
    String SIGNUP_BONUS_ELIGIBLE =
            " u.walletAddress is not null and u.status = :active"
                    + " and not exists (select 1 from Operation o"
                    + " where o.user = u and o.kind = :kind and o.status in :live)"
                    + " and not exists (select 1 from TokenLedger l where l.user = u and l.reason = :reason)";

    /** 인덱서 ②(ANT-CHAIN-11)가 이벤트의 tx 로 작업을 찾는다. 릴레이어가 전송 직후 채운 값이라 응답 시점엔 있다. */
    Optional<Operation> findByTxHash(String txHash);

    /** 전송 주체가 tx 를 못 보낸 채 남은 PENDING — "전송~markSent 사이 크래시" 창. 인덱서 ② 가 SEND_LOST 로 닫는다. */
    List<Operation> findByStatusAndTxHashIsNullAndCreatedAtBefore(Operation.Status status, Instant before);

    /** tx 는 보냈는데 이벤트가 아직 없는 PENDING. 인덱서 ② 가 receipt 로 revert·유실을 가른다. */
    List<Operation> findByStatusAndTxHashIsNotNullAndCreatedAtBefore(Operation.Status status, Instant before);

    @Query("select u.id from User u where" + SIGNUP_BONUS_ELIGIBLE + " order by u.id")
    List<Long> findSignupBonusCandidateIds(
            @Param("active") User.Status active,
            @Param("kind") Operation.Kind kind,
            @Param("live") List<Operation.Status> live,
            @Param("reason") String reason,
            Pageable page);

    @Query("select count(u) from User u where u.id = :userId and" + SIGNUP_BONUS_ELIGIBLE)
    long countSignupBonusEligible(
            @Param("userId") Long userId,
            @Param("active") User.Status active,
            @Param("kind") Operation.Kind kind,
            @Param("live") List<Operation.Status> live,
            @Param("reason") String reason);

    /** 재시도 배치의 대상. 오래된 회원부터. */
    default List<Long> signupBonusCandidates(int limit) {
        return findSignupBonusCandidateIds(
                User.Status.ACTIVE,
                Operation.Kind.SIGNUP_BONUS,
                List.of(Operation.Status.PENDING, Operation.Status.SUCCEEDED),
                TokenReason.SIGNUP_BONUS.name(),
                PageRequest.of(0, limit));
    }

    default boolean isSignupBonusEligible(Long userId) {
        return countSignupBonusEligible(
                        userId,
                        User.Status.ACTIVE,
                        Operation.Kind.SIGNUP_BONUS,
                        List.of(Operation.Status.PENDING, Operation.Status.SUCCEEDED),
                        TokenReason.SIGNUP_BONUS.name())
                > 0;
    }
}
