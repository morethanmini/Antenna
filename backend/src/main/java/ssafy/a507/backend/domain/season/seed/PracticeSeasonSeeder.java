package ssafy.a507.backend.domain.season.seed;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.domain.market.seed.SectorSeeder;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.repository.SeasonParticipantRepository;
import ssafy.a507.backend.domain.season.repository.SeasonRepository;
import ssafy.a507.backend.domain.season.service.SeasonCreateService;
import ssafy.a507.backend.domain.season.service.SeasonCreateService.SeasonSpec;

/**
 * 연습 시즌(주제) 셋을 기동 때 만들어 둔다.
 *
 * <p><b>왜 관리자 API 가 아니라 시더인가.</b> 연습 시즌은 참가자가 만드는 것이 아니라
 * 서비스가 늘 열어 두는 것이고, G-02a 연습하기 화면은 목록이 비면 그릴 것이 없다. 그런데
 * 관리자 권한 경로가 아직 없다({@code SecurityConfig} 에 role 규칙도, admin 컨트롤러도 없다) —
 * 그걸 기다리면 화면이 계속 빈 상태로 남는다. {@code SectorSeeder} 와 같은 자리·같은 규칙이다.
 *
 * <p><b>두 번 돌아도 안전하다.</b> (모드, 대표 업종, seed) 가 같은 시즌이 있으면 건너뛴다.
 * 재료가 없어도 건너뛴다 — 백필을 아직 돌리지 않은 팀원의 로컬을 기동부터 죽이지 않는다.
 *
 * <p><b>스펙에서 빠진 연습 시즌은 지운다.</b> 주제가 바뀌면 옛 시즌이 목록에 함께 남아
 * 화면이 여섯 장이 된다. 단 누구든 참가한 시즌은 남긴다 — 기록이 딸려 있다.
 *
 * <p><b>업종 시더 뒤에 돈다.</b> 종목의 업종 힌트를 {@code stocks.sector} 에서 복사하므로
 * 업종이 비어 있는 채로 돌면 힌트가 빈 시즌이 남는다.
 *
 * <p>관리자 생성 API 가 들어오면 이 파일의 {@link #SEASONS} 가 그 요청 본문의 예시가 되고,
 * 시더는 지운다.
 */
@Slf4j
@Component
@Order(SectorSeeder.ORDER + 10)
@RequiredArgsConstructor
public class PracticeSeasonSeeder implements ApplicationRunner {

    /** 전원 공통 출발선. 3,000만원 — 1주에 수십만원인 종목도 몇 주는 잡을 수 있는 금액이다. */
    private static final BigDecimal INITIAL_CASH = new BigDecimal("30000000");

    /**
     * 시즌당 종목 수 상한. 그 구간 첫날 시가총액 상위 200 — 지수(KOSPI 200)와 같은 규모다.
     * 주제 업종 밖 종목까지 같은 장에서 어떻게 움직였는지 보라고 전부 담는다. 진행 화면이
     * 종목코드·종목명 검색과 업종 필터를 붙이는 전제다.
     */
    private static final int TICKER_COUNT = 200;

    /**
     * 만들어 둘 연습 시즌. 구간은 시장을 흔든 사건으로 잡는다(2026-09-14 팀 결정).
     *
     * <p>길이는 25~44게임일이다 — 참가자가 하루씩 직접 넘기므로 80번을 누르게 하면 끝까지 가는
     * 사람이 없다. 대신 앞에 워밍업 봉 120개가 붙어 사건 전의 추세를 끌어서 볼 수 있다.
     *
     * <p><b>seed 를 2021~2025 로 올렸다(2026-09-16).</b> 워밍업을 30에서 120으로 늘렸는데
     * ({@code SeasonCreateService.WARMUP_DAYS}) 이 시더는 (모드, 대표 업종, seed) 가 같은 시즌이
     * 있으면 건너뛰므로, seed 를 그대로 두면 이미 만들어진 시즌이 옛 봉 수로 남는다. 값을 바꾸면
     * {@link #removeStale()} 이 옛 시즌을 지우고 새로 만든다 — 다만 <b>참가자가 있는 시즌은
     * 남으므로</b> 이미 누가 들어간 시즌은 옛 봉 수 그대로 목록에 함께 남는다.
     *
     * <p><b>제목이 사건을 드러낸다.</b> 예전에는 연도·사건 이름을 카드에 쓰지 않았다 — 결과를 아는
     * 채로 시작하면 예측이 아니라 복기가 되기 때문이다. 대회 모드를 접고 연습만 남기면서 뒤집었다:
     * 연습은 혼자 하는 공부라 무엇이 있었는지 알고 들어가는 편이 배우기 낫고, 어차피 뉴스 본문에
     * 사건 이름이 그대로 나온다. <b>숨기는 것은 실제 날짜·연도 하나뿐이다</b>(ERD v0.8).
     *
     * <p>{@code theme} 은 카드 배지에 쓰는 대표 업종이고 종목을 거르지 않는다. 사건이 업종을 가리지
     * 않고 시장 전체를 흔들기 때문이다 — 종목은 그 구간 첫날 시가총액 상위 200이다.
     *
     * <p>기준일은 요청값이다. 그날이 휴일이면 시즌은 그다음 영업일부터 시작한다.
     */
    private static final List<SeasonSpec> SEASONS = List.of(
            new SeasonSpec(
                    Season.Mode.PRACTICE,
                    "코로나 대확산, 멈춘 두 달",
                    "전염병이 퍼지며 시장 전체가 무너졌다가 되돌아온 구간. 공포 속에서 파는 손과 저점을 잡는 손을 연습한다.",
                    "운송·창고",
                    LocalDate.of(2020, 2, 17),
                    TICKER_COUNT,
                    44,
                    INITIAL_CASH,
                    2021L),
            new SeasonSpec(
                    Season.Mode.PRACTICE,
                    "전쟁이 터진 날",
                    "전쟁으로 원자재와 방산이 뛰고 지수는 밀린 구간. 사건이 업종마다 다르게 닿는 것을 본다.",
                    "화학",
                    LocalDate.of(2022, 2, 7),
                    TICKER_COUNT,
                    40,
                    INITIAL_CASH,
                    2022L),
            new SeasonSpec(
                    Season.Mode.PRACTICE,
                    "하루 만의 폭락, 엔캐리 청산",
                    "해외 금리와 환 변동에 하루 만에 급락한 뒤 되돌아온 구간. 패닉에 파는 것이 답인지 묻는다.",
                    "전기·전자",
                    LocalDate.of(2024, 7, 22),
                    TICKER_COUNT,
                    30,
                    INITIAL_CASH,
                    2023L),
            new SeasonSpec(
                    Season.Mode.PRACTICE,
                    "트럼프 재당선과 환율 급등",
                    "선거 결과로 환율이 뛰고 업종 희비가 갈린 구간. 정책 기대와 우려를 나눠 읽는 연습이다.",
                    "운송장비·부품",
                    LocalDate.of(2024, 10, 21),
                    TICKER_COUNT,
                    25,
                    INITIAL_CASH,
                    2024L),
            new SeasonSpec(
                    Season.Mode.PRACTICE,
                    "비상계엄과 탄핵 정국",
                    "정치 불확실성이 환율과 외국인 수급을 흔든 구간. 시끄러운 뉴스와 실제 실적을 가르는 연습이다.",
                    "은행",
                    LocalDate.of(2024, 11, 25),
                    TICKER_COUNT,
                    40,
                    INITIAL_CASH,
                    2025L));

    private final SeasonRepository seasonRepository;
    private final SeasonParticipantRepository participantRepository;
    private final SeasonCreateService createService;

    @Override
    public void run(ApplicationArguments args) {
        removeStale();
        for (SeasonSpec spec : SEASONS) {
            if (seasonRepository.existsByModeAndThemeAndSeed(
                    spec.mode(), spec.theme(), spec.seed())) {
                continue;
            }
            try {
                createService.create(spec);
            } catch (IllegalStateException e) {
                // 재료 부족은 오류가 아니다. 백필이 끝난 뒤 다음 기동이 다시 시도한다.
                log.info("연습 시즌 \"{}\" 을 건너뛴다 — {}", spec.title(), e.getMessage());
            }
        }
    }

    /** 스펙 목록에 없는 연습 시즌을 지운다. 참가자가 한 명이라도 있으면 남긴다. */
    private void removeStale() {
        Set<String> keep = SEASONS.stream().map(s -> key(s.theme(), s.seed())).collect(Collectors.toSet());
        for (Season season : seasonRepository.findByMode(Season.Mode.PRACTICE)) {
            if (keep.contains(key(season.getTheme(), season.getSeed()))) {
                continue;
            }
            if (participantRepository.existsBySeason_Id(season.getId())) {
                log.info("옛 연습 시즌 \"{}\"(id={}) 은 참가자가 있어 남긴다", season.getTitle(), season.getId());
                continue;
            }
            createService.delete(season.getId());
            log.info("옛 연습 시즌 \"{}\"(id={}) 을 지웠다 — 스펙에 없다", season.getTitle(), season.getId());
        }
    }

    private static String key(String theme, Long seed) {
        return theme + "#" + seed;
    }
}
