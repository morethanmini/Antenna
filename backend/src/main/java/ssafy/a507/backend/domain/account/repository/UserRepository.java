package ssafy.a507.backend.domain.account.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.account.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 관리자 제재 화면의 회원 찾기 (ANT-ADMIN-01). {@code q} 가 null 이면 최근 가입 순으로 앞에서부터.
     *
     * <p>닉네임 부분 일치를 {@code lower()} 로 맞춘다 — PostgreSQL 은 기본 콜레이션에서 대소문자를
     * 구분하므로 "Ant" 로 찾으면 "ant" 가 안 걸린다. 닉네임이 없는 계정(온보딩 전)은 목록에서 뺀다.
     */
    @Query("""
            select u from User u
            where u.nickname is not null
              and (:q is null or lower(u.nickname) like lower(concat('%', :q, '%')))
            order by u.id desc
            """)
    List<User> searchForAdmin(@Param("q") String q, Limit limit);

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
