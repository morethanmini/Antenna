package ssafy.a507.backend.domain.account.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ssafy.a507.backend.domain.account.entity.UserInterest;

public interface UserInterestRepository extends JpaRepository<UserInterest, Long> {

    /** 등록 순. 화면이 담은 순서대로 칩을 그린다 */
    List<UserInterest> findByUserIdOrderByIdAsc(Long userId);

    /**
     * 전체 교체의 앞단. 프로필 수정은 배열을 통째로 덮으므로 지우고 다시 넣는다(명세 §회원).
     *
     * <p>파생 delete 라 JPA 가 먼저 select 한 뒤 한 건씩 지운다. 한 사람의 관심 섹터는 많아야 수십
     * 개라 벌크 delete 로 바꿀 이유가 없고, 영속성 컨텍스트와 어긋나지 않는 편이 안전하다.
     */
    void deleteByUserId(Long userId);
}
