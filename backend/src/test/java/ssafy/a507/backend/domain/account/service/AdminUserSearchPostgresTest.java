package ssafy.a507.backend.domain.account.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;

/**
 * ANT-ADMIN-01 — 회원 찾기가 <b>실제 PostgreSQL</b> 에서 대소문자를 무시하는지 본다 (plan §검증).
 *
 * <p>H2 로는 검증이 안 되는 것: 문자 비교 방식이 다르다. compose 가 DB 를 {@code --icu-locale=ko-KR} 로
 * 고정해 두었고, PostgreSQL 은 기본 콜레이션에서 대소문자를 구분한다. {@code lower()} 를 양쪽에 걸지
 * 않으면 "Ant" 로 찾을 때 "ant운영" 이 안 걸리는데, H2 는 그 차이를 그대로 재현하지 않는다.
 *
 * <p>함께 보는 것: 닉네임이 없는 계정(온보딩 전)이 목록에서 빠지는가. 이건 로직이지만 같은 쿼리라 여기서 같이 본다.
 *
 * <p>Docker 가 없으면 건너뛴다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("관리자 회원 찾기 — PostgreSQL 대소문자")
class AdminUserSearchPostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("대문자로 찾아도 소문자 닉네임이 걸리고, 닉네임 없는 계정은 빠진다")
    void 대소문자_무시_검색() {
        insert("ant운영");
        insert("ANT관리");
        insert("전혀다른사람");
        insertWithoutNickname();

        List<String> 대문자로찾음 =
                users.searchForAdmin("ANT", Long.MAX_VALUE, Limit.of(50)).stream().map(User::getNickname).toList();
        List<String> 소문자로찾음 =
                users.searchForAdmin("ant", Long.MAX_VALUE, Limit.of(50)).stream().map(User::getNickname).toList();

        assertThat(대문자로찾음).containsExactlyInAnyOrder("ant운영", "ANT관리");
        assertThat(소문자로찾음).containsExactlyInAnyOrder("ant운영", "ANT관리");

        /* 검색어가 없을 때 서비스가 넘기는 값은 널이 아니라 빈 문자열이다 — like '%%' 가 전부 걸린다.
           널을 넘기면 concat 결과가 NULL 이 되어 한 건도 안 나온다(이 테스트가 처음에 그렇게 실패했다).

           컨테이너가 클래스 단위라 다른 테스트가 넣은 행이 함께 보인다. 그래서 "정확히 이 셋" 이
           아니라 "이 셋이 있고, 닉네임 없는 계정은 없다" 로 본다. */
        List<String> 전체 =
                users.searchForAdmin("", Long.MAX_VALUE, Limit.of(50)).stream().map(User::getNickname).toList();
        assertThat(전체).contains("ant운영", "ANT관리", "전혀다른사람");
        assertThat(전체).doesNotContainNull();
        Integer 닉네임있는행 =
                jdbc.queryForObject("select count(*) from users where nickname is not null", Integer.class);
        assertThat(전체).hasSize(닉네임있는행);
    }

    @Test
    @DisplayName("Limit 이 실제로 행 수를 자른다")
    void 상한이_걸린다() {
        for (int i = 0; i < 5; i++) insert("회원" + i);

        assertThat(users.searchForAdmin("", Long.MAX_VALUE, Limit.of(2))).hasSize(2);
    }

    private void insert(String nickname) {
        jdbc.update(
                "insert into users (nickname, role, status, created_at, updated_at)"
                        + " values (?, 'USER', 'ACTIVE', now(), now())",
                nickname);
    }

    /** 온보딩 전 계정. 목록에 뜨면 운영자가 누구인지 알 수 없는 줄을 보게 된다. */
    private void insertWithoutNickname() {
        jdbc.update(
                "insert into users (role, status, created_at, updated_at)"
                        + " values ('USER', 'ACTIVE', now(), now())");
    }
}
