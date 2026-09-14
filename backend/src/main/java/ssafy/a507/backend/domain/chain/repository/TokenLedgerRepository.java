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
