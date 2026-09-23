package ssafy.a507.backend.domain.season.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ssafy.a507.backend.common.ai.AiClient;
import ssafy.a507.backend.common.ai.AiProperties;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.entity.SeasonNews;
import ssafy.a507.backend.domain.season.entity.SeasonTrade;

/**
 * AI 복기(ANT-SEASON-09). 회차를 끝낼 때 부른다 — 호출은 회차당 1회다.
 *
 * <p>비용을 줄이려고 재료를 압축한다. 체결을 전부 싣지 않고 종목별 집계 한 줄씩과 최근
 * {@value #MAX_TRADES}건만 넣는다. 출력 길이는 지시문으로 묶는다(네 단락 · 1,200~1,500자).
 *
 * <p><b>2026-09-19 — 재료와 지시문을 넓혔다.</b> 전에는 "세 단락 · 500자" 에 성과 지표와 체결뿐이라
 * 단락마다 한 문장짜리 일반론이 나왔다(모델 탓이 아니었다 — 시킨 대로 쓴 것이다). 이제 시장 흐름 ·
 * 급락·급등일 · 업종·종목 등락({@link SeasonMarketDigest}) · 게임일별 시장 요약(season_news AI_HINT) ·
 * 체결 뒤 마지막 날까지의 등락을 함께 싣고, 주제가 된 사건이 시장을 어떻게 움직였는지부터 쓰게 한다.
 *
 * <p>키가 비어 있으면 부르지 않고 null 을 준다 — 로컬·CI 에서 종료 흐름이 막히면 안 된다.
 * 그 회차는 복기 없이 성적표만 남고, 키가 생긴 뒤 finish 를 다시 부르면 그때 채운다.
 *
 * <p>시대 단서 규칙(2026-09-19 팀장 결정): <b>사건·이슈의 이름은 말해도 된다</b> — 복기는 끝난 뒤에
 * 읽는 글이고 시즌 제목이 이미 사건을 말한다. <b>연도·날짜는 여전히 가린다</b>: 재료에 넣지 않고
 * 시점은 게임일 D+n 으로만 쓴다.
 */
@Service
@RequiredArgsConstructor
public class SeasonReviewService {

    static final int MAX_TRADES = 60;

    /** 이 지시문의 세대. 고치면 올린다 — 어느 복기가 어느 지시문으로 쓰였는지 season_results 에 남는다. */
    static final String PROMPT_VERSION = "review-4";

    /** 게임일별 시장 요약을 몇 줄까지 싣나. 시즌이 길어도 재료가 끝없이 늘지 않게 한다. */
    static final int MAX_HINTS = 60;

    public static final String INSTRUCTION =
            """
            너는 모의투자 복기 코치다. 아래 재료만 근거로 한국어 복기 리포트를 쓴다.
            문체: 모든 문장을 "~습니다" "~입니다" "~했습니다" 로 끝나는 합니다체로 쓴다. \
            "~다" "~했다" "~한다" 로 끝나는 문장은 하나도 쓰지 않는다. 이 지시문과 재료가 평서형이어도 따라 하지 않는다. \
            숫자·금액·등락률·게임일은 재료에 적힌 표기(예: -3.1%, 12,000원, D+5) 그대로 쓰고 한글로 풀어 쓰지 않는다.
            형식: 정확히 네 단락. 각 단락은 "시장 흐름:", "잘한 판단:", "아쉬운 판단:", "개선 제안:" 으로 시작한다. \
            단락마다 3~5문장, 전체 1,200~1,500자. 제목·머리말·불릿·마크다운 없이 문장만 쓰고 단락 사이에 빈 줄을 하나 둔다.
            내용: "시장 흐름" 은 이 시즌의 주제가 된 사건·이슈가 시장과 업종을 어떻게 움직였는지를 재료의 구간 등락 · \
            큰 움직임 · 게임일별 요약과 엮어 설명한다. "잘한 판단" 과 "아쉬운 판단" 은 참가자의 실제 체결을 게임일과 \
            수치로 짚고, 그때 시장이 어떤 국면이었는지와 매매 뒤 주가가 어떻게 됐는지를 연결한다. \
            재료의 "매매 비용" 이 수익을 얼마나 깎았는지도 짚는다 — 잦은 매매일수록 크다. 체결 옆의 \
            "→ 마지막 날 종가까지 x%" 는 그 체결가 대비 시즌 마지막 날 종가의 등락이다. 체결이 없으면 어느 구간·업종에 \
            기회와 위험이 있었는지를 재료로 짚는다. "개선 제안" 은 이런 국면을 다시 만났을 때 해 볼 구체적인 행동 두세 가지다.
            규칙: 재료에 없는 사실·수치를 지어내지 않는다. 재료에 나온 사건·이슈의 이름은 말해도 된다. \
            실제 연도·날짜·요일은 말하지 않고 시점은 게임일 D+n 으로만 가리킨다. \
            특정 종목의 매수·매도 권유나 수익 보장 표현을 쓰지 않는다.
            """;

    private final AiClient aiClient;
    private final AiProperties aiProperties;

    /** 복기 본문. 키가 없으면 null. GMS 실패는 AiException 그대로 올린다 — 부른 쪽이 정한다. */
    public String review(String input) {
        if (!hasKey()) {
            return null;
        }
        return aiClient.complete(INSTRUCTION, input);
    }

    public boolean hasKey() {
        return aiProperties.apiKey() != null && !aiProperties.apiKey().isBlank();
    }

    /**
     * 복기 지시문의 세대. 공용 {@code app.ai.prompt-version} 을 쓰지 않는다 — 그 값은 시장 브리핑이
     * "옛 세대 행만 다시 생성" 하는 데 쓰여서, 복기 지시문을 고쳤다고 올리면 브리핑까지 다시 만든다.
     */
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    /**
     * 프롬프트 재료. 엔티티의 지연 로딩이 여기서 일어나므로 트랜잭션 안에서 만든다.
     *
     * @param trades 이 회차의 체결, 오래된 순
     * @param market 시즌 전 종목의 종가로 만든 시장 재료
     * @param hints 게임일별 시장 요약(season_news 의 종목 없는 행 — AI_HINT), 게임일 오름차순
     */
    public static String input(
            Season season,
            List<SeasonTrade> trades,
            SeasonResultCalculator.Result r,
            SeasonMarketDigest market,
            List<SeasonNews> hints) {
        StringBuilder sb = new StringBuilder(6144);
        sb.append("시즌: ").append(season.getTitle());
        if (season.getTheme() != null && !season.getTheme().isBlank()) {
            sb.append(" · 테마 ").append(season.getTheme());
        }
        sb.append(" · ").append(season.getLengthDays()).append("게임일 · 시작 예수금 ")
                .append(won(season.getInitialCash())).append('\n');
        if (season.getNote() != null && !season.getNote().isBlank()) {
            sb.append("해설: ").append(season.getNote()).append('\n');
        }
        sb.append(market.text());
        appendHints(sb, hints, market.keyDays());

        sb.append("성과: 최종 자산 ").append(won(r.finalAsset()))
                .append(" · 수익률 ").append(pct(r.returnRate()))
                .append(" · 시장(시즌 종목 등가중) ").append(pct(r.benchmarkReturn()))
                .append(" · 최대 낙폭 ").append(pct(r.maxDrawdown()))
                .append(" · 승률 ").append(pct(r.winRate()))
                .append(" · 손익비 ").append(num(r.profitFactor()))
                .append(" · 평균 보유일 ").append(num(r.avgHoldingDays())).append('\n');

        if (trades.isEmpty()) {
            sb.append("체결: 없음 — 한 번도 매매하지 않고 끝냈습니다.\n");
            return sb.toString();
        }

        // 종목별 집계 — [매수 횟수, 매도 횟수] 와 실현손익 합
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> realized = new LinkedHashMap<>();
        BigDecimal cost = BigDecimal.ZERO;
        for (SeasonTrade t : trades) {
            cost = cost.add(t.cost());
            String name = t.getTicker().getDisplayName();
            int[] c = counts.computeIfAbsent(name, k -> new int[2]);
            c[t.getSide() == SeasonTrade.Side.BUY ? 0 : 1]++;
            if (t.getRealizedPnl() != null) {
                realized.merge(name, t.getRealizedPnl(), BigDecimal::add);
            }
        }
        sb.append("종목별: ");
        counts.forEach((name, c) -> {
            sb.append(name).append(" 매수 ").append(c[0]).append("회/매도 ").append(c[1]).append("회");
            if (realized.containsKey(name)) {
                sb.append(" 실현손익 ").append(signedWon(realized.get(name)));
            }
            sb.append(" ; ");
        });
        sb.append('\n');
        // 실현손익·수익률은 이미 비용을 뺀 값이다. 따로 적는 것은 얼마가 비용으로 나갔는지가 배울 거리라서다
        sb.append("매매 비용(수수료·거래세) 합계 ").append(won(cost)).append(" (")
                .append(trades.size()).append("건) — 실현손익·수익률은 이 비용을 뺀 값이고, 시장 수익률에는 비용이 없습니다.\n");

        int from = Math.max(0, trades.size() - MAX_TRADES);
        sb.append("체결(오래된 순");
        if (from > 0) {
            sb.append(", 앞 ").append(from).append("건 생략");
        }
        sb.append("): ");
        for (SeasonTrade t : trades.subList(from, trades.size())) {
            sb.append("D+").append(t.getGameDay()).append(' ')
                    .append(t.getSide() == SeasonTrade.Side.BUY ? "매수 " : "매도 ")
                    .append(t.getTicker().getDisplayName()).append(' ')
                    .append(t.getQty()).append("주 @").append(won(t.getPrice()));
            if (t.getRealizedPnl() != null) {
                sb.append(" (").append(signedWon(t.getRealizedPnl())).append(')');
            }
            // 판 뒤에 더 올랐는지, 산 뒤에 어떻게 됐는지 — "너무 일찍 팔았다" 를 재료로 말하게 한다
            BigDecimal since = market.sinceTrade(t.getTicker().getId(), t.getPrice());
            if (since != null) {
                sb.append(" → 마지막 날 종가까지 ").append(since.signum() > 0 ? "+" : "")
                        .append(since.toPlainString()).append('%');
            }
            sb.append(" · ");
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * 게임일별 시장 요약. 하루 한 줄(title)은 다 싣고, 세 줄짜리 본문(body)은 급락·급등일만 싣는다 —
     * 다 실으면 재료가 두 배가 되는데 모델이 실제로 기대어 쓸 날은 시장이 크게 움직인 날이다.
     */
    private static void appendHints(StringBuilder sb, List<SeasonNews> hints, List<Integer> keyDays) {
        if (hints.isEmpty()) {
            return;
        }
        sb.append("게임일별 시장 요약: ");
        hints.stream().limit(MAX_HINTS)
                .forEach(h -> sb.append("D+").append(h.getGameDay()).append(' ').append(h.getTitle()).append(" | "));
        sb.append('\n');
        List<SeasonNews> key = hints.stream()
                .filter(h -> keyDays.contains(h.getGameDay()) && h.getBody() != null && !h.getBody().isBlank())
                .toList();
        if (!key.isEmpty()) {
            sb.append("크게 움직인 날의 주요 사건: ");
            key.forEach(h -> sb.append("D+").append(h.getGameDay()).append(' ')
                    .append(h.getBody().replace('\n', ' ')).append(" | "));
            sb.append('\n');
        }
    }

    private static String won(BigDecimal v) {
        return v == null ? "-" : v.setScale(0, RoundingMode.HALF_UP).toPlainString() + "원";
    }

    private static String signedWon(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + won(v);
    }

    private static String pct(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString() + "%";
    }

    private static String num(BigDecimal v) {
        return v == null ? "-" : v.stripTrailingZeros().toPlainString();
    }
}
