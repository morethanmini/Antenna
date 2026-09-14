package ssafy.a507.backend.domain.season.seed;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.repository.SeasonRepository;

/**
 * 시즌 뉴스 시드 — 파일의 시즌 seed·종목코드를 행 id 로 바꿔 넣고, 맞지 않는 행은 버리고, 두 번 돌려도 같다.
 *
 * <p>기준 데이터 — 연습 시즌 하나(seed 2001, 5게임일)와 종목 둘(A0001·A0002).
 */
@SpringBootTest
@Transactional
class SeasonNewsSeederTest {

    @Autowired SeasonNewsSeeder seeder;
    @Autowired SeasonRepository seasonRepository;
    @Autowired EntityManager em;

    @TempDir Path dir;

    private static final String TICKER_ROW =
            "{\"seasonSeed\":2001,\"gameDay\":2,\"kind\":\"NEWS\",\"code\":\"A0001\",\"title\":\"A 수주\","
                    + "\"body\":\"발췌\",\"link\":\"https://news.example/1\",\"promptVersion\":null}";
    private static final String MARKET_ROW =
            "{\"seasonSeed\":2001,\"gameDay\":3,\"kind\":\"AI_HINT\",\"code\":null,\"title\":\"한 줄\","
                    + "\"body\":\"· 사건\",\"link\":null,\"promptVersion\":\"v1\"}";
    private static final String UNKNOWN_TICKER_ROW = TICKER_ROW.replace("A0001", "Z9999");
    private static final String OUT_OF_RANGE_ROW = TICKER_ROW.replace("\"gameDay\":2", "\"gameDay\":6");
    private static final String OTHER_SEASON_ROW = TICKER_ROW.replace("2001", "2002");

    private Long seasonId;

    @BeforeEach
    void setUp() {
        em.createNativeQuery(
                        """
                        INSERT INTO seasons (mode, title, theme, length_days, initial_cash, seed, current_day, status)
                        VALUES ('PRACTICE', '급락과 반등', '운송장비·부품', 5, 30000000, 2001, 0, 'RUNNING')
                        """)
                .executeUpdate();
        seasonId = ((Number) em.createNativeQuery("SELECT MAX(id) FROM seasons").getSingleResult()).longValue();
        for (String code : List.of("A0001", "A0002")) {
            em.createNativeQuery("INSERT INTO stocks (code, name, market, listed) VALUES (?, ?, 'KOSPI', true)")
                    .setParameter(1, code)
                    .setParameter(2, code + "종목")
                    .executeUpdate();
            em.createNativeQuery(
                            "INSERT INTO season_tickers (season_id, display_name, real_stock_code) VALUES (?, ?, ?)")
                    .setParameter(1, seasonId)
                    .setParameter(2, code + "종목")
                    .setParameter(3, code)
                    .executeUpdate();
        }
        em.flush();
        em.clear();
    }

    @Test
    @DisplayName("종목코드를 그 시즌의 종목 id 로 바꾸고, 종목 없는 행(AI 요약)은 ticker 를 비워 넣는다")
    void 넣는다() throws IOException {
        assertThat(load(TICKER_ROW, MARKET_ROW)).isEqualTo(2);

        assertThat(scalar("SELECT COUNT(*) FROM season_news WHERE season_id = " + seasonId)).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM season_news n JOIN season_tickers t ON n.ticker_id = t.id"
                        + " WHERE t.real_stock_code = 'A0001' AND n.link = 'https://news.example/1'"))
                .isEqualTo(1);
        assertThat(scalar("SELECT COUNT(*) FROM season_news WHERE ticker_id IS NULL AND kind = 'AI_HINT'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("그 시즌에 없는 종목·기간 밖 게임일·다른 시즌 행은 버린다")
    void 맞지_않는_행은_버린다() throws IOException {
        assertThat(load(TICKER_ROW, UNKNOWN_TICKER_ROW, OUT_OF_RANGE_ROW, OTHER_SEASON_ROW)).isEqualTo(1);
    }

    @Test
    @DisplayName("두 번 돌려도 같다 — 행 수가 같으면 건너뛰고, 파일이 바뀌면 그 시즌 뉴스를 갈아 끼운다")
    void 멱등() throws IOException {
        load(TICKER_ROW);
        assertThat(load(TICKER_ROW)).as("같은 파일").isZero();

        assertThat(load(TICKER_ROW, MARKET_ROW)).as("바뀐 파일").isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM season_news WHERE season_id = " + seasonId)).isEqualTo(2);
    }

    private int load(String... lines) throws IOException {
        Path file = dir.resolve("season-news-" + System.nanoTime() + ".jsonl.gz");
        try (Writer out = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            out.write(String.join("\n", lines));
        }
        List<Season> seasons = seasonRepository.findByMode(Season.Mode.PRACTICE);
        int inserted = seeder.load(new FileSystemResource(file), seasons);
        em.flush();
        em.clear();
        return inserted;
    }

    private long scalar(String sql) {
        return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue();
    }
}
