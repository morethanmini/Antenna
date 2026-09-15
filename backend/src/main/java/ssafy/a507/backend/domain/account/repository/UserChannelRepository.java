package ssafy.a507.backend.domain.account.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import ssafy.a507.backend.domain.account.entity.UserChannel;

public interface UserChannelRepository extends JpaRepository<UserChannel, Long> {

    /** 등록 순. 화면이 넣은 순서대로 링크를 그린다 */
    List<UserChannel> findByUserIdOrderByIdAsc(Long userId);

    /** 전체 교체의 앞단 — UserInterestRepository.deleteByUserId 와 같은 판단이다 */
    void deleteByUserId(Long userId);
}
