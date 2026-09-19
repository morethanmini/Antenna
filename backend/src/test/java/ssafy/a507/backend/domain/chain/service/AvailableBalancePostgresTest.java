package ssafy.a507.backend.domain.chain.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.entity.Subscription;

/**
 * ANT-TOKEN-11 — 홀드 합계 세 쿼리가 <b>실제 PostgreSQL</b> 에서 도는지 본다.
 *
 * <p>H2 로는 검증이 안 되는 것: 행이 하나도 없을 때 {@code coalesce(sum(...), 0)} 이 널이 아니라 0 을 내는지,
 * {@code numeric(30,0)} 합이 {@code BigInteger} 로 돌아오는지, JPQL 의 enum 리터럴({@code Entity$Status.PENDING})이
 * PostgreSQL 방언에서 풀리는지. 셋 중 하나라도 어긋나면 가용잔액이 NPE 로 터지거나 0 이 되어 모든 결제가 막힌다.
 *
 * <p>Docker 가 없으면 건너뛴다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("가용잔액 홀드 합계 — PostgreSQL")
class AvailableBalancePostgresTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Autowired AvailableBalanceService availableBalance;
    @Autowired EntityManager em;
    @Autowired TransactionTemplate tx;

    @Test
    @DisplayName("행이 하나도 없으면 홀드는 널이 아니라 0 이다")
    void 빈_테이블은_0() {
        User me = insertUser("아무것도없음");

        assertThat(availableBalance.held(me.getId())).isEqualTo(BigInteger.ZERO);
    }

    @Test
    @DisplayName("PENDING 광고 + PENDING 구독이 합쳐지고, 닫힌 것(REJECTED·FAILED·EXPIRED)은 빠진다")
    void 홀드_합계() {
        User me = insertUser("홀드있음");
        User publisher = insertUser("예측가");
        Instant now = Instant.now();

        tx.executeWithoutResult(s -> {
            User meRef = em.getReference(User.class, me.getId());
            User pubRef = em.getReference(User.class, publisher.getId());
            em.persist(AdBanner.request(meRef, "/x", "https://a.example.com", now, now.plus(7, ChronoUnit.DAYS), BigInteger.valueOf(7_000)));
            AdBanner closed = AdBanner.request(meRef, "/y", "https://b.example.com", now, now.plus(7, ChronoUnit.DAYS), BigInteger.valueOf(5));
            closed.reject();
            em.persist(closed);
            em.persist(Subscription.request(meRef, pubRef, BigInteger.valueOf(10_000)));
            // PENDING 을 닫는 길은 fail() 이다 — expire() 는 ACTIVE 에서만 움직인다(ANT-TOKEN-01, 2026-09-19).
            Subscription failed = Subscription.request(meRef, pubRef, BigInteger.valueOf(3));
            failed.fail();
            em.persist(failed);
            Subscription expired = Subscription.request(meRef, pubRef, BigInteger.valueOf(2));
            expired.activate("0xexpired", now);
            expired.expire();
            em.persist(expired);
        });

        assertThat(availableBalance.held(me.getId())).isEqualTo(BigInteger.valueOf(17_000));
        // 남의 홀드는 내 것이 아니다
        assertThat(availableBalance.held(publisher.getId())).isEqualTo(BigInteger.ZERO);
    }

    private User insertUser(String nickname) {
        return tx.execute(s -> {
            User u = User.create();
            u.changeNickname(nickname);
            em.persist(u);
            return u;
        });
    }
}
