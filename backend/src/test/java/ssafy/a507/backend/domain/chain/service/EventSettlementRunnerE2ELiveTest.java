package ssafy.a507.backend.domain.chain.service;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.crypto.Keys;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.commit.EventCommits;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.indexer.ChainLogSource;
import ssafy.a507.backend.domain.chain.indexer.PoolIndexer;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.relay.TokenRelayer;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;
import ssafy.a507.backend.domain.event.service.EventResultService;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * ANT-EVENT-03 — 로컬 체인 전체 리허설. 평소 빌드에서는 돌지 않는다(build.gradle 의 *E2ELiveTest 제외).
 *
 * <pre>
 * docker compose up -d chain            # Hardhat + 토큰·앵커·풀 배포 + MOVER 부여
 * CHAIN_RPC_URL=ws://127.0.0.1:8545 CHAIN_ID=31337 RELAYER_PRIVATE_KEY=0x59c6… \
 * CONTRACT_PREDICT_TOKEN=0xe7f1… CONTRACT_EVENT_POOL=0x9fE4… \
 * ./gradlew test --tests '*EventSettlementRunnerE2ELiveTest*'
 * </pre>
 *
 * <p>진짜 릴레이어·인덱서·러너를 쓰고 DB 만 H2 다. 흐름: mint → openEvent → placeBet ×3(실제 커밋) → 인덱서 → seal → 인덱서 →
 * <b>러너</b> → 인덱서 → 순위 굳힘. 지갑 잔액을 체인에서 읽어 배분식을 검산한다 — "정산 합계 = 풀 − 잔돈 · 잔액 = 체인" 이 수용 기준이다.
 *
 * <p>온체인 eventId 는 DB 행의 id 여야 하므로 큰 난수 id 로 행을 직접 넣는다(로컬 체인은 컨테이너가 살아 있는 동안 상태가 남아,
 * 같은 id 로 두 번 열 수 없다).
 */
@SpringBootTest(
        properties = {
            "app.chain.rpc-url=${CHAIN_RPC_URL}",
            "app.chain.chain-id=${CHAIN_ID:31337}",
            "app.chain.relayer.private-key=${RELAYER_PRIVATE_KEY}",
            "app.chain.predict-token.address=${CONTRACT_PREDICT_TOKEN}",
            "app.chain.event-pool.address=${CONTRACT_EVENT_POOL}",
            "app.chain.event-pool.settlement.chunk-size=2",
            "app.chain.event-pool.settlement.receipt-timeout-seconds=60",
            "app.chain.indexer.from-block=0",
            "app.chain.indexer.max-block-range=10000"
        })
@EnabledIfEnvironmentVariable(named = "CHAIN_RPC_URL", matches = "wss?://.+")
@EnabledIfEnvironmentVariable(named = "RELAYER_PRIVATE_KEY", matches = "0x[0-9a-fA-F]{64}")
@EnabledIfEnvironmentVariable(named = "CONTRACT_PREDICT_TOKEN", matches = "0x[0-9a-fA-F]{40}")
@EnabledIfEnvironmentVariable(named = "CONTRACT_EVENT_POOL", matches = "0x[0-9a-fA-F]{40}")
@Transactional
@DisplayName("정산 러너 — 로컬 체인 전체 리허설")
class EventSettlementRunnerE2ELiveTest {

    static final LocalDate BASE_DATE = LocalDate.of(2026, 9, 17);
    static final LocalDate SETTLE_DATE = LocalDate.of(2026, 9, 21);

    @Autowired EntityManager em;
    @Autowired PoolRelayer pool;
    @Autowired TokenRelayer token;
    @Autowired ChainLogSource chain;
    @Autowired PoolIndexer indexer;
    @Autowired EventSettlementRunner runner;
    @Autowired EventResultService resultService;
    @Autowired PredictionEventResultRepository results;

    private final Instant now = Instant.now();

    @Test
    @DisplayName("mint → open → 배팅 3 → seal → 러너(reveal·resolve·settle·sweep) → 인덱서 → 잔액 = 배분식 → 순위")
    void 전체_리허설() throws Exception {
        assertThat(pool.isEnabled()).isTrue();
        assertThat(token.isEnabled()).isTrue();
        long eventId = 900_000L + ThreadLocalRandom.current().nextLong(1, 99_999L);
        System.out.println("eventId = " + eventId);

        // ── 픽스처: 종목·이벤트(직접 id)·종목 칸·종가
        em.createNativeQuery("INSERT INTO stocks (code, name, listed) VALUES ('005930', '삼성전자', true), ('000660', 'SK하이닉스', true)").executeUpdate();
        em.createNativeQuery(
                        "INSERT INTO prediction_events (id, title, status, opens_at, seals_at, base_date, settle_date, min_stake, max_stake, min_stocks, created_at)"
                                + " VALUES (:id, 'E2E', 'OPEN', :o, :s, :bd, :sd, 500, 2000, 1, CURRENT_TIMESTAMP)")
                .setParameter("id", eventId)
                .setParameter("o", now.minus(1, ChronoUnit.DAYS))
                .setParameter("s", now.plus(1, ChronoUnit.DAYS))
                .setParameter("bd", BASE_DATE)
                .setParameter("sd", SETTLE_DATE)
                .executeUpdate();
        PredictionEvent event = em.find(PredictionEvent.class, eventId);
        PredictionEventStock s0 = PredictionEventStock.of(event, (short) 0, em.find(Stock.class, "005930"), new BigDecimal("91000.00"));
        PredictionEventStock s1 = PredictionEventStock.of(event, (short) 1, em.find(Stock.class, "000660"), new BigDecimal("210000.00"));
        em.persist(s0);
        em.persist(s1);
        quote("005930", "91800.00"); // UP 승
        quote("000660", "210000.00"); // 보합 → 환불
        em.flush();

        // ── 지갑 셋에 10,000 ANT
        User alice = user(freshWallet());
        User bob = user(freshWallet());
        User carol = user(freshWallet());
        for (User u : List.of(alice, bob, carol)) {
            await(token.mint(u.getWalletAddress(), BigInteger.valueOf(10_000), TokenReason.SIGNUP_BONUS));
        }

        // ── 이벤트 열기 · 배팅 3건(실제 커밋 = 러너가 리빌에 쓸 재료와 같은 식)
        await(pool.openEvent(eventId, 2, BigInteger.valueOf(500), BigInteger.valueOf(2000)));
        placeBet(alice, event, s0, Prediction.Direction.UP, 1000, true);
        placeBet(bob, event, s0, Prediction.Direction.DOWN, 500, false);
        placeBet(carol, event, s1, Prediction.Direction.UP, 700, false);
        em.flush();
        indexer.poll(now);
        em.flush();
        em.clear();
        assertThat(betOf(alice).getBetStatus()).isEqualTo(PredictionEventBet.Status.ACTIVE);
        assertThat(betOf(alice).getBetIndex()).isNotNull();
        assertThat(token.balanceOf(alice.getWalletAddress())).isEqualTo(BigInteger.valueOf(9_000));

        // ── 봉인 → 인덱서가 SEALED 로
        await(pool.seal(eventId));
        indexer.poll(now);
        em.flush();
        em.clear();
        assertThat(em.find(PredictionEvent.class, eventId).getStatus()).isEqualTo(PredictionEvent.Status.SEALED);

        // ── 러너
        EventSettlementRunner.RunReport report = runner.run(eventId);
        System.out.println("report = " + report);
        assertThat(report.revealed()).isEqualTo(3);
        assertThat(report.resolved()).isEqualTo(2);
        assertThat(report.settled()).isEqualTo(3);
        assertThat(runner.state(eventId).map(EventSettlementRun::getPhase)).contains(EventSettlementRun.Phase.DONE);
        assertThat(pool.eventInfo(eventId).status()).isEqualTo(PoolRelayer.Status.SETTLED);

        // ── 체인 잔액 = 배분식. alice 1000 → 1500 (풀 1500 을 혼자 가져감) · bob 0 · carol 환불
        assertThat(token.balanceOf(alice.getWalletAddress())).isEqualTo(BigInteger.valueOf(10_500));
        assertThat(token.balanceOf(bob.getWalletAddress())).isEqualTo(BigInteger.valueOf(9_500));
        assertThat(token.balanceOf(carol.getWalletAddress())).isEqualTo(BigInteger.valueOf(10_000));

        // ── 인덱서가 DB 를 따라 적는다 → 순위 굳힘
        indexer.poll(now);
        em.flush();
        em.clear();
        assertThat(betOf(alice).getBetStatus()).isEqualTo(PredictionEventBet.Status.SETTLED);
        assertThat(betOf(alice).getPayout()).isEqualTo(BigInteger.valueOf(1500));
        assertThat(betOf(carol).getBetStatus()).isEqualTo(PredictionEventBet.Status.REFUNDED);
        assertThat(em.find(PredictionEvent.class, eventId).getStatus()).isEqualTo(PredictionEvent.Status.SETTLED);

        resultService.freeze(eventId);
        List<PredictionEventResult> ranked = results.findRanked(eventId);
        assertThat(ranked).extracting(r -> r.getKey().getUserId()).containsExactly(alice.getId(), carol.getId(), bob.getId());
        assertThat(ranked.get(0).getNet()).isEqualTo(BigInteger.valueOf(500));

        // ── 다시 눌러도 tx 없이 끝난다
        EventSettlementRunner.RunReport again = runner.run(eventId);
        assertThat(again.alreadySettled()).isTrue();
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private static String freshWallet() throws Exception {
        return "0x" + Keys.getAddress(Keys.createEcKeyPair());
    }

    private User user(String wallet) {
        User u = User.create();
        u.linkWallet(wallet);
        em.persist(u);
        return u;
    }

    private void quote(String code, String close) {
        em.createNativeQuery("INSERT INTO daily_quotes (stock_code, trade_date, close, collected_at) VALUES (:c, :d, :p, CURRENT_TIMESTAMP)")
                .setParameter("c", code)
                .setParameter("d", SETTLE_DATE)
                .setParameter("p", new BigDecimal(close))
                .executeUpdate();
    }

    /** REST 묶음 등록과 같은 순서: 예측·커밋·배팅(PENDING)·작업 저장 → placeBet. 상태는 인덱서가 올린다. */
    private void placeBet(User user, PredictionEvent event, PredictionEventStock stock, Prediction.Direction dir, long stake, boolean hit) throws Exception {
        Prediction p =
                Prediction.register(
                        user, stock.getStock(), dir, new BigDecimal("93000.00"), new BigDecimal("91000.00"), (short) 4, BASE_DATE, SETTLE_DATE);
        p.fixBasePrice(stock.getBasePrice());
        p.settle(new BigDecimal("91800.00"), SETTLE_DATE, hit, new BigDecimal("1.000"));
        em.persist(p);
        em.flush();
        String salt = "ab".repeat(31) + String.format("%02x", p.getId() % 256);
        String noteHash = CommitHashes.noteHash("e2e-" + p.getId(), salt);
        em.createNativeQuery("INSERT INTO prediction_commits (prediction_id, commit_hash, salt, note_hash) VALUES (:id, :h, :s, :n)")
                .setParameter("id", p.getId())
                .setParameter("h", "0x" + String.format("%064x", p.getId()))
                .setParameter("s", salt)
                .setParameter("n", noteHash)
                .executeUpdate();
        Operation op = Operation.accept(user, Operation.Kind.EVENT_BET, Operation.ResourceType.PREDICTION, p.getId());
        em.persist(op);
        byte[] commit = EventCommits.commit(event.getId(), stock.getIdx(), dir, noteHash);
        PredictionEventBet b =
                PredictionEventBet.place(p, event, stock, BigInteger.valueOf(stake), org.web3j.utils.Numeric.toHexString(commit), op.getId());
        em.persist(b);
        em.flush();
        String tx = pool.placeBet(event.getId(), user.getWalletAddress(), stock.getIdx(), BigInteger.valueOf(stake), commit);
        op.markSent(tx);
        await(tx);
    }

    private PredictionEventBet betOf(User user) {
        return em.createQuery("select b from PredictionEventBet b where b.user.id = :u", PredictionEventBet.class)
                .setParameter("u", user.getId())
                .getSingleResult();
    }

    private void await(String txHash) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            Optional<Boolean> status = chain.receiptStatus(txHash);
            if (status.isPresent()) {
                assertThat(status.get()).as("tx " + txHash).isTrue();
                return;
            }
            Thread.sleep(500);
        }
        throw new AssertionError("receipt 없음: " + txHash);
    }
}
