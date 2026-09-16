package ssafy.a507.backend.domain.account.repository;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ssafy.a507.backend.domain.account.entity.UserInterest;

public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

    /** 등록 순. 화면이 담은 순서대로 칩을 그린다 */
    List<UserInterest> findByUserIdOrderByIdAsc(Long userId);

    /**
     * 여러 사람을 한 번에. 목록 화면(E-03 내 구독)이 줄마다 찾으면 N+1 이라 따로 둔다.
     *
     * <p>정렬을 (userId, id) 로 거는 이유 — 부르는 쪽이 userId 로 묶을 때 한 사람의 섹터가
     * 흩어지지 않고, 그 안의 순서는 위 메서드와 같은 '담은 순서' 다.
     */
    List<UserInterest> findByUserIdInOrderByUserIdAscIdAsc(Collection<Long> userIds);

    /**
     * 전체 교체의 앞단. 프로필 수정은 배열을 통째로 덮으므로 지우고 다시 넣는다(명세 §회원).
     *
     * <p>파생 delete 라 JPA 가 먼저 select 한 뒤 한 건씩 지운다. 한 사람의 관심 섹터는 많아야 수십
     * 개라 벌크 delete 로 바꿀 이유가 없고, 영속성 컨텍스트와 어긋나지 않는 편이 안전하다.
     */
    void deleteByUserId(Long userId);
}
