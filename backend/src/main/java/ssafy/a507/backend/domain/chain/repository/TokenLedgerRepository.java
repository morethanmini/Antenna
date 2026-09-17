package ssafy.a507.backend.domain.chain.repository;

import java.math.BigInteger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;

public interface TokenLedgerRepository extends JpaRepository<TokenLedger, Long> {

    /**
     * 잔액. 원장이 append-only 라 SUM(delta) 가 곧 잔액이다.
     *
     * <p>행이 하나도 없으면 SUM 이 NULL 이라 {@link java.util.Optional} 로 받는다 —
     * 원시 타입으로 받으면 신규 회원 조회에서 NPE 가 난다.
     */
    @Query("select sum(l.delta) from TokenLedger l where l.user.id = :userId")
    java.util.Optional<BigInteger> sumDeltaByUserId(@Param("userId") Long userId);

    default BigInteger balanceOf(Long userId) {
        return sumDeltaByUserId(userId).orElse(BigInteger.ZERO);
    }

    /**
     * 기간 안에 <b>들어온</b> ANT 합 (ANT-TOKEN-04 · 화면 B-01 · H-01).
     *
     * <p>사유를 가리지 않는다. 배팅했다가 환불받으면 이 합과 나간 합이 둘 다 오르지만, 그래야 {@code 들어온 것 -
     * 나간 것 = 그 기간의 잔액 변화} 가 성립해 나중에 숫자끼리 검산이 된다.
     *
     * <p>위 {@link #sumDeltaByUserId} 와 같은 이유로 {@link java.util.Optional} 로 받는다 — 맞는 행이 없으면
     * SUM 이 NULL 이다.
     */
    @Query(
            "select sum(l.delta) from TokenLedger l"
                    + " where l.user.id = :userId and l.createdAt >= :from and l.delta > 0")
    java.util.Optional<BigInteger> sumEarnedSince(
            @Param("userId") Long userId, @Param("from") java.time.Instant from);

    /**
     * 기간 안에 <b>나간</b> ANT 합. 원장 값이 음수라 <b>음수로 돌아온다</b> — 뒤집는 것은 서비스가 한다.
     *
     * <p>유입·유출을 {@code case when} 하나로 묶어 한 번에 셀 수도 있지만 두 벌로 나눴다. 위 잔액 쿼리와 같은 모양이라
     * 반환 타입이 어긋날 여지가 없고, 둘 다 {@code (user_id, created_at)} 인덱스를 그대로 타서 구간이 좁다 — 한 번 더
     * 훑는 값이 그 위험보다 싸다.
     */
    @Query(
            "select sum(l.delta) from TokenLedger l"
                    + " where l.user.id = :userId and l.createdAt >= :from and l.delta < 0")
    java.util.Optional<BigInteger> sumSpentSince(
            @Param("userId") Long userId, @Param("from") java.time.Instant from);

    /**
     * 지갑 원장 목록 (ANT-TOKEN-04) — 최신부터, 커서 id 미만, reason 선택. 커밋 원장 목록({@code AnchorBatchRepository.findPage})과
     * 같은 커서 규칙이다. 근거 이벤트를 같이 읽는다 — 행마다 tx 해시를 다시 묻지 않으려는 것(대부분 이벤트가 있다).
     */
    @Query("""
            select l from TokenLedger l left join fetch l.chainEvent
             where l.user.id = :userId
               and (:reason is null or l.reason = :reason)
               and (:cursorId is null or l.id < :cursorId)
             order by l.id desc
            """)
    java.util.List<TokenLedger> findPage(
            @Param("userId") Long userId,
            @Param("reason") String reason,
            @Param("cursorId") Long cursorId,
            org.springframework.data.domain.Limit limit);
}
