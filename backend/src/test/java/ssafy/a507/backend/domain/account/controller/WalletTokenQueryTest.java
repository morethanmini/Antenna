package ssafy.a507.backend.domain.account.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import java.math.BigInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.entity.ChainEvent;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;
import ssafy.a507.backend.support.TestNonceStoreConfig;

/**
 * GET /api/v1/wallet/balance · /ledger (ANT-TOKEN-04). 원장은 인덱서가 쓰는 것과 같은 모양(이벤트 + 원장 행)으로 em 에 직접 넣는다.
 *
 * <p>애노테이션을 {@link WalletControllerTest} 와 똑같이 둔다 — 스프링 테스트 컨텍스트 캐시를 같이 쓰려는 것. 이 컨텍스트는 RPC 가 비어
 * 토큰 인덱서가 꺼져 있다 → {@code syncedAt} 은 null 이다(켜진 경우는 {@code TokenIndexerTest}).
 *
 * <p>픽스처 이름이 {@code newUser()} 인 이유: {@code user()} 로 지으면 시큐리티의 {@code user(String)} 정적 임포트를 가려 인증 호출이 전부 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestNonceStoreConfig.class)
@Transactional
@DisplayName("지갑 잔액·원장 조회 API")
class WalletTokenQueryTest {

    private static final String TOKEN = "0xe7f1725e7734ce288f8367e1bb143e90bb3f0512";

    @Autowired MockMvc mockMvc;
    @Autowired EntityManager em;

    private int seq;

    @Test
    @DisplayName("잔액은 원장 합이다 — 정수 ANT 문자열 · symbol ANT · 인덱서가 꺼져 있으면 syncedAt 은 null")
    void 잔액은_원장_합이다() throws Exception {
        User u = newUser();
        credit(u, 10_000, "SIGNUP_BONUS");
        credit(u, -1_000, "AD_PAY");

        mockMvc.perform(get("/api/v1/wallet/balance").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("9000"))
                .andExpect(jsonPath("$.symbol").value("ANT"))
                .andExpect(jsonPath("$.syncedAt").isEmpty());
    }

    @Test
    @DisplayName("원장이 없으면 잔액 \"0\" — 지갑 미연동 회원도 에러가 아니다")
    void 원장이_없으면_잔액_0() throws Exception {
        User u = User.create();
        em.persist(u);
        em.flush();

        mockMvc.perform(get("/api/v1/wallet/balance").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("0"));
        mockMvc.perform(get("/api/v1/wallet/ledger").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("원장은 최신순 — 커서는 마지막으로 받은 id, 그보다 작은 id 가 다음 페이지")
    void 원장은_최신순_커서_페이징() throws Exception {
        User u = newUser();
        credit(u, 10_000, "SIGNUP_BONUS");
        TokenLedger second = credit(u, -1_000, "AD_PAY");
        credit(u, -1_000, "SLOT_OVER");
        String me = String.valueOf(u.getId());

        mockMvc.perform(get("/api/v1/wallet/ledger").param("size", "2").with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].reason").value("SLOT_OVER"))
                .andExpect(jsonPath("$.items[0].delta").value("-1000"))
                .andExpect(jsonPath("$.items[1].reason").value("AD_PAY"))
                .andExpect(jsonPath("$.hasNext").value(true))
                .andExpect(jsonPath("$.nextCursor").value(second.getId()));

        mockMvc.perform(get("/api/v1/wallet/ledger").param("size", "2").param("cursor", String.valueOf(second.getId())).with(user(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("SIGNUP_BONUS"))
                .andExpect(jsonPath("$.items[0].delta").value("10000"))
                .andExpect(jsonPath("$.hasNext").value(false))
                .andExpect(jsonPath("$.nextCursor").isEmpty());
    }

    @Test
    @DisplayName("reason 으로 거른다")
    void reason_필터() throws Exception {
        User u = newUser();
        credit(u, 10_000, "SIGNUP_BONUS");
        credit(u, -1_000, "AD_PAY");

        mockMvc.perform(get("/api/v1/wallet/ledger").param("reason", "AD_PAY").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].reason").value("AD_PAY"));
    }

    @Test
    @DisplayName("어휘에 없는 reason 이면 400 INVALID_REQUEST · field=reason")
    void 없는_reason_은_400() throws Exception {
        User u = newUser();

        mockMvc.perform(get("/api/v1/wallet/ledger").param("reason", "NOPE").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("reason"));
    }

    @Test
    @DisplayName("txHash 는 근거 이벤트의 tx — 이벤트 없이 들어간 행이면 null")
    void txHash_는_근거_이벤트() throws Exception {
        User u = newUser();
        TokenLedger withEvent = credit(u, 10_000, "SIGNUP_BONUS");
        em.createNativeQuery("INSERT INTO token_ledger (user_id, delta, reason, created_at) VALUES (?, ?, 'SIGNUP_BONUS', CURRENT_TIMESTAMP)")
                .setParameter(1, u.getId())
                .setParameter(2, BigInteger.valueOf(5))
                .executeUpdate();

        mockMvc.perform(get("/api/v1/wallet/ledger").with(user(String.valueOf(u.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].txHash").isEmpty())
                .andExpect(jsonPath("$.items[1].txHash").value(withEvent.getChainEvent().getTxHash()));
    }

    @Test
    @DisplayName("남의 원장은 섞이지 않는다")
    void 남의_원장은_안_보인다() throws Exception {
        User me = newUser();
        User other = newUser();
        credit(other, 10_000, "SIGNUP_BONUS");

        mockMvc.perform(get("/api/v1/wallet/ledger").with(user(String.valueOf(me.getId()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(get("/api/v1/wallet/balance").with(user(String.valueOf(me.getId()))))
                .andExpect(jsonPath("$.balance").value("0"));
    }

    @Test
    @DisplayName("로그인하지 않으면 401")
    void 미인증은_401() throws Exception {
        mockMvc.perform(get("/api/v1/wallet/balance")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/wallet/ledger")).andExpect(status().isUnauthorized());
    }

    // ── 픽스처 ──────────────────────────────────────────────

    private User newUser() {
        User u = User.create();
        u.linkWallet("0x" + String.format("%040x", System.nanoTime() + (++seq)));
        em.persist(u);
        em.flush();
        return u;
    }

    /** 인덱서가 쓰는 모양 그대로 — 체인 이벤트 한 행 + 원장 한 행. */
    private TokenLedger credit(User u, long delta, String reason) {
        seq++;
        ChainEvent event = ChainEvent.record(
                "0x" + String.format("%064x", 7_000_000L + seq), 0, TOKEN, delta >= 0 ? "Minted" : "Burned", 100 + seq, "{}");
        em.persist(event);
        TokenLedger row = TokenLedger.record(u, BigInteger.valueOf(delta), reason, event);
        em.persist(row);
        em.flush();
        return row;
    }
}
