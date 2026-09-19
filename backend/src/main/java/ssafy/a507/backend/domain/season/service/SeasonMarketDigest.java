package ssafy.a507.backend.domain.season.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * AI 복기 재료의 <b>시장 쪽</b>(ANT-SEASON-09 · 2026-09-19).
 *
 * <p>전에는 복기에 성과 지표 한 줄과 체결 목록만 실었다. 시장이 언제 빠지고 언제 돌아왔는지,
 * 어느 업종이 움직였는지가 재료에 없으니 모델은 "성급하게 매매하지 않았다" 같은 일반론밖에 쓸 수
 * 없었다 — 지시문이 재료에 없는 사실을 금지하기 때문이다. 여기서 시즌 전 종목의 종가로 그 재료를
 * 만든다.
 *
 * <p>엔티티를 받지 않는다. 계산만 하는 값이라 {@link Quote} 로 받아야 테스트가 DB 없이 돈다.
 * 시점은 전부 게임일 D+n 이다 — 실제 날짜는 이 클래스에 들어오지도 않는다.
 *
 * <p>"시장" 은 성적표의 벤치마크와 같은 뜻이다: 시즌 종목 등가중(첫 게임일 = 1).
 */
public final class SeasonMarketDigest {

    /** 한 종목의 한 게임일 종가. */
    public record Quote(long tickerId, String name, String sector, int gameDay, BigDecimal close) {}

    private static final int TOP = 3;
    private static final int MOVES = 2;

    private final String text;
    private final List<Integer> keyDays;
    private final Map<Long, BigDecimal> lastClose;

    private SeasonMarketDigest(String text, List<Integer> keyDays, Map<Long, BigDecimal> lastClose) {
        this.text = text;
        this.keyDays = keyDays;
        this.lastClose = lastClose;
    }

    /** 재료 문단. 가격이 없으면 빈 문자열 — 복기 자체를 막지 않는다. */
    public String text() {
        return text;
    }

    /** 급락·급등일. 이날의 "주요 사건" 을 뉴스에서 찾아 같이 싣는다. 게임일 오름차순. */
    public List<Integer> keyDays() {
        return keyDays;
    }

    /** 체결가 대비 마지막 날 종가의 등락(%). 그 종목의 가격을 모르면 null. */
    public BigDecimal sinceTrade(Long tickerId, BigDecimal tradePrice) {
        BigDecimal last = lastClose.get(tickerId);
        if (last == null || tradePrice == null || tradePrice.signum() == 0) {
            return null;
        }
        return last.subtract(tradePrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(tradePrice, 1, RoundingMode.HALF_UP);
    }

    /**
     * @param quotes 시즌 전 종목의 게임일 1 ~ lastDay 종가. 순서는 상관없다
     * @param lastDay 시즌의 총 게임일
     */
    public static SeasonMarketDigest of(List<Quote> quotes, int lastDay) {
        Map<Long, TreeMap<Integer, BigDecimal>> closes = new HashMap<>();
        Map<Long, Quote> tickers = new LinkedHashMap<>();
        for (Quote q : quotes) {
            if (q.gameDay() < 1 || q.gameDay() > lastDay) {
                continue;
            }
            closes.computeIfAbsent(q.tickerId(), k -> new TreeMap<>()).put(q.gameDay(), q.close());
            tickers.putIfAbsent(q.tickerId(), q);
        }
        if (closes.isEmpty()) {
            return new SeasonMarketDigest("", List.of(), Map.of());
        }

        double[] index = index(closes, lastDay);
        Map<Long, BigDecimal> last = new HashMap<>();
        Map<Long, Double> returns = new HashMap<>();
        closes.forEach((id, series) -> {
            BigDecimal base = series.firstEntry().getValue();
            BigDecimal end = series.lastEntry().getValue();
            last.put(id, end);
            if (base.signum() > 0) {
                returns.put(id, (end.doubleValue() / base.doubleValue() - 1) * 100);
            }
        });

        List<Integer> keyDays = new ArrayList<>();
        StringBuilder sb = new StringBuilder(512);
        sb.append("시장 흐름(시즌 종목 등가중): ").append(segments(index, lastDay)).append('\n');
        String moves = moves(index, lastDay, keyDays);
        if (!moves.isEmpty()) {
            sb.append("큰 움직임(전 게임일 대비): ").append(moves).append('\n');
        }
        sb.append("업종(첫날→마지막 날): ").append(sectors(tickers, returns)).append('\n');
        sb.append("종목(첫날→마지막 날): ").append(leaders(tickers, returns)).append('\n');
        keyDays.sort(Comparator.naturalOrder());
        return new SeasonMarketDigest(sb.toString(), List.copyOf(keyDays), last);
    }

    /** 등가중 지수. index[d] = 종목별 (그날까지의 마지막 종가 ÷ 첫 종가) 의 평균. index[0] 은 안 쓴다. */
    private static double[] index(Map<Long, TreeMap<Integer, BigDecimal>> closes, int lastDay) {
        double[] index = new double[lastDay + 1];
        for (int day = 1; day <= lastDay; day++) {
            double sum = 0;
            int n = 0;
            for (TreeMap<Integer, BigDecimal> series : closes.values()) {
                Map.Entry<Integer, BigDecimal> at = series.floorEntry(day);
                BigDecimal base = series.firstEntry().getValue();
                if (at == null || base.signum() == 0) {
                    continue;
                }
                sum += at.getValue().doubleValue() / base.doubleValue();
                n++;
            }
            index[day] = n == 0 ? (day == 1 ? 1 : index[day - 1]) : sum / n;
        }
        return index;
    }

    /** 초반·중반·후반. 세 구간이 안 나올 만큼 짧으면 한 구간으로 적는다. */
    private static String segments(double[] index, int lastDay) {
        if (lastDay < 3) {
            return "D+1~D+" + lastDay + " " + pct((index[lastDay] / index[1] - 1) * 100);
        }
        int[] ends = {lastDay / 3, lastDay * 2 / 3, lastDay};
        List<String> parts = new ArrayList<>();
        int start = 1;
        for (int end : ends) {
            double from = index[Math.max(1, start - 1)];
            parts.add("D+" + start + "~D+" + end + " " + pct((index[end] / from - 1) * 100));
            start = end + 1;
        }
        return String.join(" · ", parts);
    }

    /** 가장 크게 내린 날·오른 날. 내린 날만 급락, 오른 날만 급등으로 센다. */
    private static String moves(double[] index, int lastDay, List<Integer> keyDays) {
        record Move(int day, double change) {}
        List<Move> all = new ArrayList<>();
        for (int day = 2; day <= lastDay; day++) {
            all.add(new Move(day, (index[day] / index[day - 1] - 1) * 100));
        }
        List<Move> drops = all.stream().filter(m -> m.change() < 0)
                .sorted(Comparator.comparingDouble(Move::change)).limit(MOVES).toList();
        List<Move> rises = all.stream().filter(m -> m.change() > 0)
                .sorted(Comparator.comparingDouble(Move::change).reversed()).limit(MOVES).toList();
        drops.forEach(m -> keyDays.add(m.day()));
        rises.forEach(m -> keyDays.add(m.day()));

        List<String> parts = new ArrayList<>();
        if (!drops.isEmpty()) {
            parts.add("급락 " + drops.stream().map(m -> "D+" + m.day() + " " + pct(m.change()))
                    .collect(Collectors.joining(", ")));
        }
        if (!rises.isEmpty()) {
            parts.add("급등 " + rises.stream().map(m -> "D+" + m.day() + " " + pct(m.change()))
                    .collect(Collectors.joining(", ")));
        }
        return String.join(" · ", parts);
    }

    private static String sectors(Map<Long, Quote> tickers, Map<Long, Double> returns) {
        Map<String, List<Double>> bySector = new LinkedHashMap<>();
        returns.forEach((id, r) -> {
            String sector = tickers.get(id).sector();
            bySector.computeIfAbsent(sector == null || sector.isBlank() ? "기타" : sector, k -> new ArrayList<>())
                    .add(r);
        });
        Map<String, Double> avg = new LinkedHashMap<>();
        bySector.forEach((sector, list) ->
                avg.put(sector, list.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        return upsAndDowns(avg, "강세", "약세");
    }

    private static String leaders(Map<Long, Quote> tickers, Map<Long, Double> returns) {
        Map<String, Double> named = new LinkedHashMap<>();
        returns.forEach((id, r) -> {
            Quote q = tickers.get(id);
            named.put(q.name() + "(" + q.sector() + ")", r);
        });
        return upsAndDowns(named, "많이 오른", "많이 내린");
    }

    /** 오른 것 상위 셋 · 내린 것 하위 셋. 한쪽이 비면 그쪽은 적지 않는다. */
    private static String upsAndDowns(Map<String, Double> values, String upLabel, String downLabel) {
        String ups = values.entrySet().stream().filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(TOP)
                .map(e -> e.getKey() + " " + pct(e.getValue())).collect(Collectors.joining(", "));
        String downs = values.entrySet().stream().filter(e -> e.getValue() < 0)
                .sorted(Map.Entry.comparingByValue()).limit(TOP)
                .map(e -> e.getKey() + " " + pct(e.getValue())).collect(Collectors.joining(", "));
        List<String> parts = new ArrayList<>();
        if (!ups.isEmpty()) {
            parts.add(upLabel + " " + ups);
        }
        if (!downs.isEmpty()) {
            parts.add(downLabel + " " + downs);
        }
        return parts.isEmpty() ? "변동 없음" : String.join(" · ", parts);
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%+.1f%%", value);
    }
}
