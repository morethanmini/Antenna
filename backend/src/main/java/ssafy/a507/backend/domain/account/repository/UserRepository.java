package ssafy.a507.backend.domain.account.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import ssafy.a507.backend.domain.account.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    boolean existsByNickname(String nickname);

    /** 지갑은 계정당 1개다. UQ 위반을 기다리지 않고 먼저 걸러 낸다. */
    boolean existsByWalletAddress(String walletAddress);

    /**
     * 봉인 크론(ANT-EVENT-05)이 작업 행의 사용자 칸을 채우려고 관리자 하나를 집는다 — 크론에는
     * 로그인 주체가 없고 {@code operations.user_id} 는 NOT NULL 이다. 관리자가 여럿이면 누가 되든
     * 상관없다: 작업의 주인이 아니라 "시스템이 보냈다" 를 적을 칸이다.
     */
    Optional<User> findFirstByRoleOrderByIdAsc(User.Role role);

    /** 인덱서 ②(ANT-CHAIN-11)가 이벤트의 지갑 주소(소문자)로 회원을 찾는다. 없으면 회원이 아닌 지갑 — 원장에 안 쓴다. */
    Optional<User> findByWalletAddress(String walletAddress);
}
