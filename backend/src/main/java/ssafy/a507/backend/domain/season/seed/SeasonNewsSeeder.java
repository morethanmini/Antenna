package ssafy.a507.backend.domain.season.seed;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.domain.market.seed.SectorSeeder;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.entity.SeasonTicker;
import ssafy.a507.backend.domain.season.repository.SeasonNewsRepository;
import ssafy.a507.backend.domain.season.repository.SeasonRepository;
import ssafy.a507.backend.domain.season.repository.SeasonTickerRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 연습 시즌 뉴스를 파일에서 채운다 (ANT-DATA-08 · ANT-SEASON-07).
 *
 * <p><b>왜 파일 시드인가.</b> 뉴스는 과거 데이터셋(30GB)에서 파이썬으로 고르고 GPU 로 판정한 결과다
 * ({@code data/season_news/}). 운영 DB 에 손으로 넣지 않고 {@code resources/seed/season-news.jsonl.gz}
 * 로 들고 다니면 로컬·운영이 같은 뉴스를 갖는다. 파일이 없으면 조용히 건너뛴다.
 *
 * <p><b>시즌 시더 뒤에 돈다.</b> 파일은 시즌을 {@code seed}, 종목을 실제 종목코드로 가리키고, 여기서
 * 그 시즌의 행 id 로 바꾼다. 그 시즌에 없는 종목(운영과 로컬의 종목 구성이 다를 때)과 기간 밖
 * 게임일 행은 버린다.
 *
 * <p><b>시즌마다 한 트랜잭션.</b> 이미 들어 있는 행 수가 넣을 행 수와 같으면 건너뛰고, 다르면 그
 * 시즌 뉴스를 지우고 다시 넣는다 — 파일을 갈아 끼우면 다음 기동에 반영된다.
 * ponytail: 행 수만 본다. 같은 수로 내용만 바뀐 파일은 반영되지 않는다 — 그런 일이 생기면 파일에 판(版)을 적는다.
 */
@Slf4j
@Component
@Order(SectorSeeder.ORDER + 20)
@RequiredArgsConstructor
public class SeasonNewsSeeder implements ApplicationRunner {

    static final String SEED_PATH = "seed/season-news.jsonl.gz";

    private static final String INSERT =
            "INSERT INTO season_news (season_id, ticker_id, game_day, kind, title, body, link, prompt_version)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SeasonRepository seasonRepository;
    private final SeasonTickerRepository seasonTickerRepository;
    private final SeasonNewsRepository seasonNewsRepository;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate tx;

    /** 파일 한 줄. 종목 없는 행(AI 요약)은 {@code code} 가 null 이다. */
    record Row(
            long seasonSeed,
            int gameDay,
            String kind,
            String code,
            String title,
            String body,
            String link,
            String promptVersion) {}

    @Override
    public void run(ApplicationArguments args) throws IOException {
        List<Season> seasons = seasonRepository.findByMode(Season.Mode.PRACTICE);
        Resource resource = new ClassPathResource(SEED_PATH);
        if (seasons.isEmpty() || !resource.exists()) {
            log.info("시즌 뉴스 시드를 건너뛴다 — 연습 시즌 {}개, 파일 {}", seasons.size(), resource.exists());
            return;
        }
        load(resource, seasons);
    }

    /** @return 새로 넣은 행 수 */
    int load(Resource resource, List<Season> seasons) throws IOException {
        Map<Long, List<Row>> bySeed = read(resource);
        int inserted = 0;
        for (Season season : seasons) {
            List<Row> rows = bySeed.getOrDefault(season.getSeed(), List.of());
            if (!rows.isEmpty()) {
                inserted += tx.execute(status -> replace(season, rows));
            }
        }
        return inserted;
    }

    private int replace(Season season, List<Row> rows) {
        Map<String, Long> tickerIds = seasonTickerRepository.findBySeason_IdOrderByDisplayNameAsc(season.getId())
                .stream()
                .collect(Collectors.toMap(t -> t.getRealStock().getCode(), SeasonTicker::getId, (a, b) -> a));
        List<Object[]> params = new ArrayList<>();
        for (Row r : rows) {
            Long tickerId = r.code() == null ? null : tickerIds.get(r.code());
            if ((r.code() != null && tickerId == null) || r.gameDay() < 1 || r.gameDay() > season.getLengthDays()) {
                continue;
            }
            params.add(new Object[] {
                season.getId(), tickerId, r.gameDay(), r.kind(), r.title(), r.body(), r.link(), r.promptVersion()
            });
        }
        if (seasonNewsRepository.countBySeason_Id(season.getId()) == params.size()) {
            return 0;
        }
        seasonNewsRepository.deleteBySeasonId(season.getId());
        jdbcTemplate.batchUpdate(INSERT, params);
        log.info("시즌 뉴스 적재 — \"{}\"(id={}) {}행 (파일 {}행, 버림 {}행)",
                season.getTitle(), season.getId(), params.size(), rows.size(), rows.size() - params.size());
        return params.size();
    }

    private static Map<Long, List<Row>> read(Resource resource) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(resource.getInputStream()), StandardCharsets.UTF_8))) {
            return in.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> JSON.readValue(line, Row.class))
                    .collect(Collectors.groupingBy(Row::seasonSeed, LinkedHashMap::new, Collectors.toList()));
        }
    }
}
