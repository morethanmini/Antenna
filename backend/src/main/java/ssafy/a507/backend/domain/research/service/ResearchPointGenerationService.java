package ssafy.a507.backend.domain.research.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import ssafy.a507.backend.common.ai.AiClient;
import ssafy.a507.backend.common.ai.AiException;
import ssafy.a507.backend.common.ai.AiProperties;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.market.repository.DailyQuoteRepository;
import ssafy.a507.backend.domain.research.entity.ResearchDocument;
import ssafy.a507.backend.domain.research.entity.ResearchPoint;
import ssafy.a507.backend.domain.research.repository.ResearchDocumentRepository;
import ssafy.a507.backend.domain.research.repository.ResearchPointRepository;

/**
 * 긍정·위험·확인 포인트 생성 (ANT-RESEARCH-04).
 *
 * <p><b>요청 시점에 만든다(2026-09-09 부터).</b> 원래는 배치 B6 가 매일 상장 종목 전부(≈300)를
 * 미리 만들었는데, 실제로 열리는 종목은 소수라 GMS 토큰이 하루 만에 바닥났다. 지금은 사용자가
 * 예측 탭에서 포인트를 요청한 종목만, 최신 거래일 기준으로 한 번 만들어 저장한다 — 같은 종목·
 * 같은 거래일의 다음 요청은 DB 에서 바로 나간다.
 *
 * <p>브리핑과 재료·기준일이 같다({@link StockMaterials}). 다른 것은 두 가지다.
 *
 * <p><b>① 응답을 JSON 배열로 받는다.</b> 브리핑은 줄글이라 첫 줄만 떼면 됐지만 포인트는 3열 × N건
 * 이고 건마다 근거 문서를 가리켜야 한다. 줄글에서 "이 문장의 근거는 몇 번 문서"를 뽑아내려면
 * 파서를 새로 쓰는 셈이라, 애초에 구조로 받는다.
 *
 * <p><b>② 건 단위로 버린다.</b> 브리핑은 한 건이 곧 한 응답이라 D16 에 걸리면 통째로 버렸다.
 * 포인트는 아홉 건 중 하나만 이상해도 나머지는 쓸 만하다 — 이상한 건만 버리고 남는 것을 저장한다.
 * 우리가 준 목록 밖의 {@code documentId} 를 지목한 건도 여기서 걸러진다("근거 보기"가 엉뚱한
 * 기사로 가는 것이 근거 없는 것보다 나쁘다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchPointGenerationService {

    /** 한 열(긍정·위험·확인)에 넣는 최대 건수. 화면이 카드 3장까지 보여 준다. */
    private static final int MAX_PER_KIND = 3;

    /** 재료로 넣는 원천별 문서 수. 뉴스 5 + 공시 5 면 프롬프트가 2천 자를 넘지 않는다. */
    private static final int DOCS_PER_SOURCE = 5;

    /** {@code research_points.body} 컬럼 길이. 넘는 건은 잘라 붙이지 않고 버린다. */
    private static final int MAX_BODY = 300;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 응답을 읽기만 하는 용도라 앱의 직렬화 설정을 물려받지 않는다(AiClient 와 같은 이유). */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * v3(2026-09-23): 문장을 "사실 + 그것이 왜 긍정·위험·확인거리인지" 로 쓰게 바꿨다.
     *
     * <p>v2 는 교내 GPU 의 양자화 모델(Qwen2.5-32B-AWQ)이 숫자를 잘못 옮기고 전망어를 붙이던 때에 맞춰
     * 평가 어휘를 통째로 막고 예시도 "부채비율이 1084.2%다." 한 줄이었다. 모델이 그 예시를 따라 입력
     * 한 줄씩을 옮겨 적기만 했고, "kind 마다 2~3개" 를 채우느라 거래량 0.7배 같은 평범한 값까지 칸을
     * 메웠다(QA 09-23 "성의가 없다"). 포인트는 이제 {@link AiClient}(GMS·Gemini·OpenAI)로 만들고 자체
     * 서빙은 관련도 판정 전용이라 문체를 푼다. 지어낸 숫자는 여전히 {@link #numbersGrounded} 가,
     * 확률·방향 예측은 {@code BriefingGenerationService.FORBIDDEN} 이 막는다.
     */
    private static final String INSTRUCTION =
            """
            너는 한국 주식 리서치 화면의 "긍정 · 위험 · 확인" 3열 포인트를 만든다. 개인 투자자가 한 줄씩
            읽고 "그래서 이게 왜 중요한지"를 알 수 있게 쓴다. 주어진 수치는 서버가 계산한 값이다.

            [형식]
            - JSON 배열 하나만 출력한다. 설명·머리말·마크다운 코드펜스를 붙이지 않는다.
            - 원소는 {"kind": "POSITIVE" | "RISK" | "CHECK", "body": "...", "documentId": 숫자 또는 null} 이다.
            - kind 마다 1~3개, 전체 9개를 넘기지 않는다. 두 번째·세 번째는 근거가 충분할 때만 쓴다.
              칸을 채우려고 쓰지 않는다.
            - documentId 는 아래 "문서" 목록에 있는 번호만 쓴다. 목록에 없는 번호를 지어내지 않는다.
              특정 문서가 아니라 시세·재무 수치에서 나온 포인트는 null 로 둔다.

            [무엇을 쓰나]
            - POSITIVE: 투자 판단에 우호적인 사실. RISK: 불리하거나 부담이 되는 사실. CHECK: 아직 결론이
              나지 않아 앞으로 확인할 일 — 계약·수주의 이행, 실적 발표, 공시 후속 절차, 흐름이 이어지는지.
              이미 끝난 수치를 늘어놓는 것은 CHECK 가 아니다. 어느 쪽인지 애매한 사실은 CHECK 로 둔다.
            - 문서(뉴스·공시)에 근거한 포인트를 먼저 쓴다. 재무·시세 수치는 문서가 없거나 수치 자체가
              눈에 띌 때 쓴다.
            - 눈에 띄지 않는 수치는 쓰지 않는다 — 거래량이 평균의 0.5~1.5배, 52주 범위 내 위치가 20~80%,
              1영업일 등락이 2% 미만인 것처럼 평범한 값.
            - 이 회사의 사업과 무관한 문서(같은 이름의 스포츠 구단·지역·인물 소식 등)는 쓰지 않는다.

            [어떻게 쓰나]
            - body 는 한국어 평서문 한 문장, 100자 이내. 이모지·목록 기호를 쓰지 않는다.
            - 사실과 그 사실이 왜 긍정·위험·확인거리인지를 한 문장에 함께 쓴다.
              나쁜 예: "2025년 부채비율은 149.2%이고 ROE는 4.1%다." (입력을 옮겨 적기만 했다)
              좋은 예: "2025년 영업이익이 전년보다 24.10% 줄어 영업이익률이 12.3%에서 9.1%로 낮아졌다."
            - 관련된 수치는 한 포인트로 묶는다(매출·영업이익·영업이익률, 계약 금액·기간).
            - 방향을 풀어 쓰는 말은 쓴다 — 늘었다, 줄었다, 개선됐다, 악화됐다, 낮아졌다, 전환했다.
              평가는 입력 안의 비교(전년 대비·기간 등락·52주 범위)에 기대어서만 한다. 비교 대상 없이
              "높다", "낮다", "양호하다" 로 판정하지 않는다.
            - 입력에 없는 비교 대상을 끌어오지 않는다 — "시장 기대치", "컨센서스", "업종 평균", "동종업계".
            - 입력에 없는 인과를 지어내지 않는다. 문서가 원인을 말하지 않으면 "~때문에" 로 잇지 않는다.

            [숫자]
            - 숫자는 주어진 값을 표기·단위까지 그대로 옮긴다(쉼표 포함). 억원을 조원으로 바꾸거나
              반올림·환산하지 않는다. 입력에 없는 숫자가 한 개라도 들어간 원소는 버려진다.
            - 음수 금액은 부호를 떼고 손실로 쓴다 — 입력 "영업이익 -1,568" 은 "영업손실 1,568억원".
            - 입력에 없는 셈(횟수·기간)은 숫자 대신 우리말로 쓴다 — "두 해 연속".
            - 등락률을 인용할 때는 "3.2% 상승했다"처럼 수치를 앞에 쓴다. "상승 3.2%" 처럼 방향을
              앞세우지 않는다.

            [금지]
            - 매수·매도 권유, 목표주가, 주가 방향 예측, 전망 — "기대된다", "우려된다", "~할 것이다".
            - 상승·하락의 확률이나 가능성을 수치로 쓰지 않는다. "확률"이라는 단어를 쓰지 않는다.

            예시 — 입력(일부):
            종가 31,000원 · 1영업일 -1.90% · 5영업일 +0.65% · 20영업일 +9.73%
            거래량 1,204,331주 · 최근 20영업일 평균의 2.4배
            연간 재무(CFS, 억원): 2025년 매출 52,310 영업이익 4,120 순이익 2,980; 2024년 매출 48,775 영업이익 5,406 순이익 3,870
            2025년 전년 대비: 매출 +7.25% · 영업이익 -23.79% · 순이익 -23.00% · 영업이익률 7.9% (2024년 11.1%)
            부채비율 84.6% · ROE 6.2% (2025년 기준)
            문서(번호 · 날짜 · 원천 · 내용):
            - [7] 2026-09-03 · 뉴스 · 북미 완성차 업체와 배터리 소재 장기 공급 계약 · 2027년부터 5년간 공급한다.
            - [9] 2026-09-01 · 공시 · 단일판매ㆍ공급계약체결 · 계약금액 20,145억원, 계약기간 2027-01-01~2031-12-31
            예시 — 출력:
            [{"kind":"POSITIVE","body":"북미 완성차 업체와 20,145억원 규모 공급 계약을 맺어 2027년부터 5년간 이어질 매출처를 확보했다.","documentId":9},{"kind":"RISK","body":"2025년 영업이익이 전년보다 23.79% 줄어 영업이익률이 11.1%에서 7.9%로 낮아졌다.","documentId":null},{"kind":"RISK","body":"2025년 매출은 7.25% 늘었지만 순이익은 23.00% 줄어 외형 성장이 이익으로 이어지지 않았다.","documentId":null},{"kind":"CHECK","body":"공급 계약이 2027-01-01부터 이행되므로 2027년 실적에 실제로 반영되는지 확인할 필요가 있다.","documentId":9},{"kind":"CHECK","body":"기준일 거래량이 최근 20영업일 평균의 2.4배로 늘어 이런 거래가 이어지는지 지켜볼 만하다.","documentId":null}]
            """;

    /** 본문에서 숫자를 뽑는다 — 쉼표 천 단위, 소수점 포함. */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final StockMaterials stockMaterials;
    private final ResearchPointRepository researchPointRepository;
    private final ResearchDocumentRepository researchDocumentRepository;
    private final NewsRelevanceFilter newsRelevanceFilter;
    private final DailyQuoteRepository dailyQuoteRepository;

    /**
     * 종목별 잠금. 두 사용자가 같은 종목을 같은 순간 열면 둘 다 "없음"으로 보고 두 번 부른다 — 락 안에서
     * 다시 확인한다. ponytail: 서버 인스턴스가 하나라 JVM 락으로 족하다. 스케일아웃하면 Redis 락으로.
     */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    /**
     * 한 종목의 포인트를 최신 거래일 기준으로 만든다. 이미 있으면 부르지 않는다.
     *
     * @return 이번 호출로 저장한 행 수 · 이미 있거나 재료(키·시세)가 없으면 0
     * @throws AiException 생성 실패 — 요청 경로라 삼키지 않는다. 호출부가 사용자에게 알린다
     */
    public int generate(Stock stock) {
        if (!aiProperties.isConfigured()) {
            log.info("[POINT] AI_API_KEY 가 없어 포인트를 만들지 않는다 stock={}", stock.getCode());
            return 0;
        }
        LocalDate targetDate = dailyQuoteRepository.findLatestTradeDate().orElse(null);
        if (targetDate == null) {
            log.warn("[POINT] daily_quotes 가 비어 있어 포인트를 만들지 않는다 — 일봉 수집이 먼저다");
            return 0;
        }
        synchronized (locks.computeIfAbsent(stock.getCode(), code -> new Object())) {
            int written = generateStock(stock, targetDate);
            if (written > 0) {
                log.info("[POINT] {} · 기준일 {} · {}건 생성", stock.getCode(), targetDate, written);
            }
            return written;
        }
    }

    private int generateStock(Stock stock, LocalDate targetDate) {
        String promptVersion = aiProperties.promptVersion();
        if (researchPointRepository.existsByStock_CodeAndTargetDateAndPromptVersion(
                stock.getCode(), targetDate, promptVersion)) {
            // 이 세대로 한 건이라도 있으면 넘어간다. 아홉 건 중 일부가 버려져 열이 비었어도 다시
            // 부르지 않는다 — 재시도를 열어 두면 포인트가 적게 나오는 종목을 매 회차 다시 불러 한도를
            // 태운다. 다음 영업일이 새 기준일로 다시 만든다.
            return 0;
        }
        String facts = stockMaterials.facts(stock, targetDate);
        if (facts == null) {
            // 그날 거래정지였거나 시세가 아직 안 들어온 종목. 재료 없이 만들면 지어낸 포인트가 된다.
            return 0;
        }
        Map<Long, ResearchDocument> documents = documents(stock.getCode(), targetDate);
        String input = facts + documentBlock(documents);

        String text;
        try {
            text = aiClient.complete(INSTRUCTION, input);
        } catch (AiException e) {
            if (researchPointRepository.existsByStock_CodeAndTargetDate(stock.getCode(), targetDate)) {
                // 세대를 올린 뒤 다시 만들다 실패했다. 같은 기준일의 옛 세대가 있으니 그것을 보여 주고
                // 다음 조회가 다시 해 본다 — 503 을 내면 멀쩡히 보이던 포인트가 오류로 바뀐다.
                log.warn("[POINT] 새 세대 생성 실패 — 옛 세대를 그대로 둔다 stock={} — {}", stock.getCode(), e.getMessage());
                return 0;
            }
            throw e;
        }
        List<ResearchPoint> points = parse(text, input, stock, targetDate, documents, promptVersion);
        if (points.isEmpty()) {
            // 전부 버려졌으면 저장하지 않는다 — 빈 채로 두면 다음 요청이 다시 만들어 본다.
            log.warn("[POINT] 쓸 수 있는 포인트가 없어 저장하지 않는다 stock={}", stock.getCode());
            return 0;
        }
        researchPointRepository.saveAll(points);
        return points.size();
    }

    // ── 재료 ─────────────────────────────────────────────────

    /**
     * 근거로 지목할 수 있는 문서 — 뉴스와 DART 공시.
     *
     * <p>기준일 자정(KST) 이전 것만 본다. 브리핑과 같은 이유다 — 그날 저녁 들어온 D 기사가 D-1
     * 시세 재료에 붙으면 "급등 소식"과 마이너스 등락률이 한 프롬프트에 들어간다.
     */
    private Map<Long, ResearchDocument> documents(String stockCode, LocalDate targetDate) {
        Instant endOfTarget = targetDate.plusDays(1).atStartOfDay(KST).toInstant();
        Map<Long, ResearchDocument> documents = new LinkedHashMap<>();
        for (ResearchDocument.Source source : List.of(ResearchDocument.Source.NEWS, ResearchDocument.Source.DART)) {
            // 무관 기사가 섞여 있으므로 쓸 개수의 두 배를 뽑아 거른 뒤 자른다.
            List<ResearchDocument> candidates =
                    researchDocumentRepository.findByStock_CodeAndSourceAndPublishedAtBeforeOrderByPublishedAtDesc(
                            stockCode, source, endOfTarget, Limit.of(DOCS_PER_SOURCE * 2));
            newsRelevanceFilter.keepRelevant(candidates).stream()
                    .limit(DOCS_PER_SOURCE)
                    .forEach(d -> documents.put(d.getId(), d));
        }
        return documents;
    }

    private static String documentBlock(Map<Long, ResearchDocument> documents) {
        if (documents.isEmpty()) {
            return "문서: (없음)\n";
        }
        StringBuilder sb = new StringBuilder("문서(번호 · 날짜 · 원천 · 내용):\n");
        documents.values().forEach(d -> sb.append("- [").append(d.getId()).append("] ")
                .append(d.getPublishedAt().atZone(KST).toLocalDate())
                .append(" · ").append(d.getSource() == ResearchDocument.Source.DART ? "공시" : "뉴스")
                .append(" · ").append(d.excerpt())
                .append('\n'));
        return sb.toString();
    }

    // ── 파싱 ─────────────────────────────────────────────────

    /** 배열 밖의 군말은 무시하고, 규칙을 어긴 원소는 그 건만 버린다. */
    private List<ResearchPoint> parse(
            String text, String input, Stock stock, LocalDate targetDate, Map<Long, ResearchDocument> documents,
            String promptVersion) {
        JsonNode array = array(text, stock.getCode());
        if (array == null) {
            return List.of();
        }
        Map<ResearchPoint.Kind, Integer> counts = new EnumMap<>(ResearchPoint.Kind.class);
        List<ResearchPoint> points = new ArrayList<>();
        for (JsonNode node : array) {
            ResearchPoint.Kind kind = kind(node.path("kind").asText(""));
            String body = node.path("body").asText("").trim();
            if (kind == null || body.isEmpty() || body.length() > MAX_BODY) {
                log.debug("[POINT] 포인트 한 건을 버린다 — kind·body 규칙 위반 stock={}", stock.getCode());
                continue;
            }
            if (BriefingGenerationService.FORBIDDEN.matcher(body).find()) {
                log.warn("[POINT] D16 위반 문구가 있어 포인트 한 건을 버린다 stock={}", stock.getCode());
                continue;
            }
            if (!numbersGrounded(body, input)) {
                log.warn("[POINT] 입력에 없는 숫자가 있어 포인트 한 건을 버린다 stock={} body={}", stock.getCode(), body);
                continue;
            }
            Long documentId = documentId(node.path("documentId"));
            ResearchDocument document = documentId == null ? null : documents.get(documentId);
            if (documentId != null && document == null) {
                // 우리가 주지 않은 번호다. 근거만 지우고 살리면 "근거 보기"가 없는 카드가 되는데,
                // 모델이 없는 문서를 지어냈다는 것은 body 도 그 문서를 근거로 썼다는 뜻이다.
                log.warn("[POINT] 목록 밖 documentId={} 라 포인트 한 건을 버린다 stock={}", documentId, stock.getCode());
                continue;
            }
            if (counts.merge(kind, 1, Integer::sum) > MAX_PER_KIND) {
                continue;
            }
            points.add(ResearchPoint.of(stock, targetDate, kind, body, document, promptVersion));
        }
        return points;
    }

    /**
     * 본문의 모든 숫자가 입력에 있는지. 양자화된 자체 서빙 모델이 "340000억원"을 "340억원"으로 옮기거나
     * 없는 "시장 기대치 9%"를 붙이는 것을 막는다 — 규칙이 "새 수치를 만들지 않는다"라 숫자가 입력
     * 밖이면 그 건은 지어낸 것이다.
     *
     * <p><b>부분 문자열이 아니라 값으로 견준다.</b> 부분 문자열로 보면 입력의 "340000" 안에 "340" 이
     * 들어 있어 단위 오독이 통과한다 — 이 가드를 만든 계기를 정작 못 잡는다.
     *
     * <p>값으로 견주면 표기 차이는 저절로 같아진다 — "28" 과 "28.0", "9" 와 "09"(날짜를 우리말로
     * 옮긴 경우). 반올림("1.9" 와 "1.90")은 다른 값이라 걸린다. 지시문이 반올림을 금지한다.
     */
    static boolean numbersGrounded(String body, String input) {
        Set<String> allowed = numbers(input);
        for (String number : numbers(body)) {
            if (!allowed.contains(number)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> numbers(String text) {
        Set<String> numbers = new HashSet<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            numbers.add(canonical(matcher.group()));
        }
        return numbers;
    }

    /** 표기가 달라도 같은 값이면 같은 문자열이 되게 한다 — 쉼표를 지우고 꼬리 0 을 떼어 낸다. */
    private static String canonical(String token) {
        String digits = token.replace(",", "");
        try {
            return new BigDecimal(digits).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            // 정규식이 "1,,2" 같은 것도 집을 수 있다. 값으로 못 읽으면 글자 그대로 견준다.
            return digits;
        }
    }

    /** 코드펜스나 인사말이 붙어 와도 첫 {@code [} 부터 마지막 {@code ]} 까지를 배열로 읽는다. */
    private JsonNode array(String text, String stockCode) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            log.warn("[POINT] JSON 배열이 없는 응답이라 포인트를 버린다 stock={}", stockCode);
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(text.substring(start, end + 1));
            return root.isArray() ? root : null;
        } catch (JsonProcessingException e) {
            log.warn("[POINT] 포인트 응답을 읽지 못했다 stock={} — {}", stockCode, e.getMessage());
            return null;
        }
    }

    /**
     * 번호로 읽히는 값은 모양을 가리지 않고 받는다.
     *
     * <p>모델이 {@code "documentId": "42"}(문자열)나 {@code 42.0}(실수)로 싸서 내는 일이 흔하다.
     * 정수만 받으면 그런 건이 "근거 없는 종합 포인트"로 저장돼 사용자가 근거 링크를 잃는다 —
     * 목록 검사까지 태워야 살릴 건은 살리고 지어낸 번호는 버린다.
     */
    private static Long documentId(JsonNode node) {
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual()) {
            try {
                return Long.valueOf(node.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static ResearchPoint.Kind kind(String value) {
        for (ResearchPoint.Kind kind : ResearchPoint.Kind.values()) {
            if (kind.name().equalsIgnoreCase(value.trim())) {
                return kind;
            }
        }
        return null;
    }
}
