package ssafy.a507.backend.domain.season.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.season.config.SeasonFeeProperties;
import ssafy.a507.backend.domain.season.config.SeasonReviewExecutor;
import ssafy.a507.backend.domain.season.dto.MyPositionResponse;
import ssafy.a507.backend.domain.season.dto.MySeasonStatusResponse;
import ssafy.a507.backend.domain.season.dto.SeasonAdvanceResponse;
import ssafy.a507.backend.domain.season.dto.SeasonFinishResponse;
import ssafy.a507.backend.domain.season.dto.SeasonOrderRequest;
import ssafy.a507.backend.domain.season.dto.SeasonOrderResponse;
import ssafy.a507.backend.domain.season.dto.SeasonResultResponse;
import ssafy.a507.backend.domain.season.dto.SeasonTradeItemResponse;
import ssafy.a507.backend.domain.season.dto.SeasonTradeListResponse;
import ssafy.a507.backend.domain.season.entity.Season;
import ssafy.a507.backend.domain.season.entity.SeasonNews;
import ssafy.a507.backend.domain.season.entity.SeasonParticipant;
import ssafy.a507.backend.domain.season.entity.SeasonPosition;
import ssafy.a507.backend.domain.season.entity.SeasonPrice;
import ssafy.a507.backend.domain.season.entity.SeasonResult;
import ssafy.a507.backend.domain.season.entity.SeasonTicker;
import ssafy.a507.backend.domain.season.entity.SeasonTrade;
import ssafy.a507.backend.domain.season.repository.SeasonNewsRepository;
import ssafy.a507.backend.domain.season.repository.SeasonParticipantRepository;
import ssafy.a507.backend.domain.season.repository.SeasonPositionRepository;
import ssafy.a507.backend.domain.season.repository.SeasonPriceRepository;
import ssafy.a507.backend.domain.season.repository.SeasonRepository;
import ssafy.a507.backend.domain.season.repository.SeasonResultRepository;
import ssafy.a507.backend.domain.season.repository.SeasonTickerRepository;
import ssafy.a507.backend.domain.season.repository.SeasonTradeRepository;

/**
 * 시즌 진행 — 내 현황 · 주문 · 체결 내역(ANT-SEASON-03). 전부 <b>내 마지막 회차</b>가 기준이다.
 *
 * <p><b>체결 규칙(명세 §모의투자).</b> 게임일 종가 단일가, 부분 체결·슬리피지 없음. 체결가는
 * 서버가 내 진행일 봉에서 읽는다 — 요청에 가격이 없으니 커닝할 자리도 없다.
 *
 * <p><b>매매 비용(2026-09-23).</b> 위탁수수료가 매수·매도 양쪽에, 증권거래세가 매도에 붙는다
 * (요율은 {@link SeasonFeeProperties}). 금액은 체결 행에 박제한다 — 예수금은 원장이고 성적표는
 * 체결 재생이라, 요율을 다시 곱하는 방식이면 둘이 어긋난다.
 *
 * <p><b>예수금은 로컬 원장이다.</b> 금융망 출금·입금(ANT-SEASON-06)은 붙이지 않는다.
 * {@code season_participants.cash} 를 바로 더하고 뺀다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(SeasonFeeProperties.class)
public class SeasonPlayService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final SeasonRepository seasonRepository;
    private final SeasonParticipantRepository participantRepository;
    private final SeasonTickerRepository seasonTickerRepository;
    private final SeasonPriceRepository seasonPriceRepository;
    private final SeasonPositionRepository positionRepository;
    private final SeasonTradeRepository tradeRepository;
    private final SeasonResultRepository resultRepository;
    private final SeasonNewsRepository newsRepository;
    private final UserRepository userRepository;
    private final SeasonFeeProperties fees;
    private final SeasonReviewService reviewService;
    private final BadgeService badgeService;
    private final SeasonReviewExecutor reviewExecutor;
    /** finish 가 트랜잭션을 손으로 나누는 데 쓴다 — LLM 호출을 트랜잭션 밖에 두려고. */
    private final TransactionTemplate tx;
    /** 복기를 만드는 중인 회차(participantId). {@link #scheduleReview} 가 넣고 끝나면 뺀다. */
    private final Set<Long> reviewing = ConcurrentHashMap.newKeySet();

    /** 내 현황. 보유 종목은 내 진행일 종가로 평가한다. */
    @Transactional(readOnly = true)
    public MySeasonStatusResponse me(Long userId, Long seasonId) {
        SeasonParticipant me = myAttempt(userId, seasonId);
        List<SeasonPosition> held = positionRepository.findByParticipant_IdOrderByIdAsc(me.getId());

        Map<Long, BigDecimal> closes = seasonPriceRepository
                .findByTicker_IdInAndGameDay(
                        held.stream().map(p -> p.getTicker().getId()).toList(), me.getCurrentDay())
                .stream()
                .collect(Collectors.toMap(
                        p -> p.getTicker().getId(), SeasonPrice::getClose));

        BigDecimal stockValue = BigDecimal.ZERO;
        for (SeasonPosition p : held) {
            stockValue = stockValue.add(valueOf(p, closes));
        }
        BigDecimal totalAsset = me.getCash().add(stockValue);
        BigDecimal initialCash = me.getSeason().getInitialCash();
        BigDecimal pnl = totalAsset.subtract(initialCash);

        List<MyPositionResponse> positions = held.stream()
                .map(p -> {
                    BigDecimal value = valueOf(p, closes);
                    BigDecimal cost = p.getAvgPrice().multiply(BigDecimal.valueOf(p.getQty()));
                    return new MyPositionResponse(
                            p.getTicker().getId(),
                            p.getTicker().getDisplayName(),
                            p.getQty(),
                            p.getAvgPrice(),
                            value,
                            value.subtract(cost),
                            percent(value, totalAsset));
                })
                .toList();

        return new MySeasonStatusResponse(
                me.getCash(), totalAsset, stockValue, pnl, percent(pnl, initialCash),
                me.getCurrentDay(), me.getStatus(), positions);
    }

    /**
     * 주문. 매수는 예수금, 매도는 보유 수량을 검사하고 409 로 거절한다.
     * 포지션은 수량 가중 평단으로 갱신하고 0 이 되면 지운다. 체결은 append 다.
     *
     * <p><b>비용은 대금 밖에서 더하고 뺀다.</b> 매수는 대금 + 수수료를 예수금에서 빼고
     * (그래서 예수금과 <b>같은</b> 금액의 매수도 수수료만큼 모자라 409 다 — 화면의 "최대" 가
     * 같은 식으로 수량을 구해야 하는 이유), 매도는 대금에서 수수료·거래세를 뗀 금액만 넣는다.
     *
     * <p><b>평단에는 수수료를 얹지 않는다.</b> 평단은 화면에서 차트의 가격과 나란히 읽히는
     * 값이라, 원가법으로 올리면 "70,000 에 샀는데 평단이 70,010" 이 된다. 대신 그 몫을
     * 실현손익에서 뺀다 — 아래 {@link #realizedPnlOf}.
     */
    @Transactional
    public SeasonOrderResponse order(Long userId, Long seasonId, SeasonOrderRequest request) {
        SeasonParticipant me = lockedOngoing(userId, seasonId);
        SeasonTicker ticker = seasonTickerRepository
                .findByIdAndSeason_Id(request.tickerId(), seasonId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_TICKER_NOT_FOUND));
        BigDecimal price = seasonPriceRepository
                .findByTicker_IdAndGameDay(ticker.getId(), me.getCurrentDay())
                .map(SeasonPrice::getClose)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_PRICE_NOT_FOUND));

        int qty = request.qty();
        BigDecimal amount = price.multiply(BigDecimal.valueOf(qty));
        BigDecimal fee = fees.commission(amount);
        BigDecimal tax = BigDecimal.ZERO;
        BigDecimal realizedPnl = null;

        if (request.side() == SeasonTrade.Side.BUY) {
            if (me.getCash().compareTo(amount.add(fee)) < 0) {
                throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
            }
            me.debit(amount.add(fee));
            positionRepository
                    .findByParticipant_IdAndTicker_Id(me.getId(), ticker.getId())
                    .ifPresentOrElse(
                            p -> p.buy(qty, price, fee),
                            () -> positionRepository.save(SeasonPosition.open(me, ticker, qty, price, fee)));
        } else {
            SeasonPosition held = positionRepository
                    .findByParticipant_IdAndTicker_Id(me.getId(), ticker.getId())
                    .filter(p -> p.getQty() >= qty)
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_INSUFFICIENT_QTY));
            tax = fees.sellTax(amount);
            BigDecimal boughtFee = held.sell(qty);
            realizedPnl = realizedPnlOf(price, held.getAvgPrice(), qty, fee.add(tax).add(boughtFee));
            me.credit(amount.subtract(fee).subtract(tax));
            if (held.getQty() == 0) {
                positionRepository.delete(held);
            }
        }

        SeasonTrade trade = tradeRepository.save(SeasonTrade.of(
                me, ticker, request.side(), qty, price, me.getCurrentDay(), fee, tax, realizedPnl));
        return new SeasonOrderResponse(trade.getId(), price, trade.getGameDay(), fee, tax);
    }

    /**
     * 매도의 실현손익 — <b>왕복 비용을 뺀 순액</b>이다(2026-09-23). {@code cost} 는 이 매도의
     * 수수료·거래세와, 판 수량이 살 때 실제로 낸 수수료의 몫({@link SeasonPosition#sell})이다.
     *
     * <p>매수 수수료를 요율로 되짚지 않는다 — 수수료를 붙이기 전에 산 물량은 공짜였으므로 그 몫은 0 이다.
     * 그래서 전량을 정리하면 <b>실현손익의 합 = 예수금의 변화</b> 가 평단의 반올림(소수 둘째 자리)만큼의
     * 오차 안에서 맞는다 — 매매일지의 "실현손익" 합계와 현황의 예수금이 서로를 검산한다.
     */
    private static BigDecimal realizedPnlOf(BigDecimal price, BigDecimal avgPrice, int qty, BigDecimal cost) {
        return price.subtract(avgPrice).multiply(BigDecimal.valueOf(qty)).subtract(cost);
    }

    /** 체결 내역. 최근 체결이 먼저고 커서는 id 다. */
    @Transactional(readOnly = true)
    public SeasonTradeListResponse trades(
            Long userId, Long seasonId, Long tickerId, SeasonTrade.Side side,
            Long cursor, Integer size) {
        SeasonParticipant me = myAttempt(userId, seasonId);
        int pageSize = pageSize(size);

        // 한 건 더 읽어 다음 페이지가 있는지 본다(댓글 목록과 같은 방식).
        List<SeasonTrade> found = tradeRepository.findPage(
                me.getId(), tickerId, side, cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<SeasonTrade> page = hasNext ? found.subList(0, pageSize) : found;

        List<SeasonTradeItemResponse> items = page.stream()
                .map(t -> new SeasonTradeItemResponse(
                        t.getId(),
                        t.getTicker().getId(),
                        t.getTicker().getDisplayName(),
                        t.getSide(),
                        t.getQty(),
                        t.getPrice(),
                        t.amount(),
                        t.getFee(),
                        t.getTax(),
                        t.getGameDay(),
                        t.getRealizedPnl()))
                .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new SeasonTradeListResponse(items, nextCursor, hasNext);
    }

    /**
     * 다음 게임일(ANT-SEASON-04). {@code expectedDay} 는 낙관적 잠금이다 — 서버의 진행일과 다르면
     * 넘기지 않는다. 두 번 눌러도, 탭이 둘이어도 하루만 간다. 마지막 게임일에서는 더 가지 않고
     * 409 로 종료를 가리킨다.
     */
    @Transactional
    public SeasonAdvanceResponse advance(Long userId, Long seasonId, int expectedDay) {
        SeasonParticipant me = lockedOngoing(userId, seasonId);
        Season season = me.getSeason();
        if (season.getMode() == Season.Mode.COMPETITION) {
            throw new BusinessException(ErrorCode.SEASON_ADVANCE_NOT_ALLOWED);
        }
        if (me.getCurrentDay() != expectedDay) {
            throw new BusinessException(ErrorCode.DAY_MISMATCH);
        }
        if (me.getCurrentDay() >= season.getLengthDays()) {
            throw new BusinessException(ErrorCode.SEASON_LAST_DAY);
        }
        me.advance();
        return new SeasonAdvanceResponse(me.getCurrentDay(), me.getCurrentDay() == season.getLengthDays());
    }

    /**
     * 종료 — 회차를 DONE 으로 굳히고 {@code season_results} 를 1회 만든다. 이미 끝난 회차면 저장된
     * 결과를 그대로 준다(멱등).
     *
     * <p><b>AI 복기(ANT-SEASON-09)는 여기서 기다리지 않는다(2026-09-26).</b> 전에는 성적표와 복기를 한
     * 요청에서 만들어 GMS 가 답할 때까지(수십 초) 화면이 스피너로 서 있었고, 복기가 실패하면 종료 자체가
     * 안 됐다. 지금은 ① 트랜잭션 하나로 지표를 굳히고 바로 돌아온 뒤 ② {@link SeasonReviewExecutor} 가
     * 복기를 뒤따라 만들어 {@code review_body} 에 채운다. 화면은 성적표를 먼저 그리고 복기 자리만
     * {@code GET /result/me} 를 다시 읽어 채운다({@code reviewPending}). 복기가 실패하면 null 로 남고,
     * finish 를 다시 부르면 지표는 그대로 둔 채 복기만 다시 만든다(키가 없던 회차도 같다).
     *
     * <p><b>건너뛰고 종료</b>({@code skipRemaining}, 2026-09-19 · QA 09-18 "30일을 일일이 다 눌러야 함").
     * 마지막 게임일 전이어도 받는다 — 남은 날을 <b>주문 없이 넘긴 것으로 치고</b> 마지막 날 종가로
     * 정산한다(팀장 결정). "다음 영업일" 을 남은 만큼 누른 것과 결과가 같다: 성과 계산은 원래부터
     * {@code lengthDays} 까지의 종가를 쓰므로({@link #compute}) 계산은 손대지 않고, 게임일만 마지막 날로
     * 옮긴다. 수동 진행이 없는 대회는 건너뛸 수도 없다.
     */
    public SeasonFinishResponse finish(Long userId, Long seasonId, boolean skipRemaining) {
        Finished f = tx.execute(status -> commit(userId, seasonId, skipRemaining));
        if (f.needsReview()) {
            scheduleReview(f.response().participantId());
        }
        return f.response();
    }

    /** 마지막 게임일 전인데 끝내려는가 — 건너뛰기가 아니면 409, 대회면 건너뛰기도 409. */
    private static void requireLastDayOrSkip(SeasonParticipant me, Season season, boolean skipRemaining) {
        if (me.getCurrentDay() >= season.getLengthDays()) {
            return;
        }
        if (!skipRemaining) {
            throw new BusinessException(ErrorCode.SEASON_NOT_LAST_DAY);
        }
        if (season.getMode() == Season.Mode.COMPETITION) {
            throw new BusinessException(ErrorCode.SEASON_ADVANCE_NOT_ALLOWED);
        }
    }

    /** 굳힌 성적표와, 복기를 뒤따라 만들어야 하는지(복기가 비어 있고 키가 있으면). */
    private record Finished(SeasonFinishResponse response, boolean needsReview) {}

    private Finished commit(Long userId, Long seasonId, boolean skipRemaining) {
        SeasonParticipant me = participantRepository
                .lockById(myAttempt(userId, seasonId).getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_NOT_JOINED));
        if (me.getStatus() == SeasonParticipant.Status.DONE) {
            SeasonResult saved = resultRepository.findById(me.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_ATTEMPT_ENDED));
            return new Finished(toResponse(saved), saved.getReviewBody() == null && reviewService.hasKey());
        }
        if (!me.isOngoing()) {
            throw new BusinessException(ErrorCode.SEASON_ATTEMPT_ENDED);
        }
        Season season = me.getSeason();
        requireLastDayOrSkip(me, season, skipRemaining);
        List<SeasonTrade> trades = tradeRepository.findByParticipant_IdOrderByIdAsc(me.getId());
        SeasonResultCalculator.Result r = compute(season, trades);

        me.skipTo(season.getLengthDays());
        me.finish();
        SeasonResult result = SeasonResult.of(
                me, r.finalAsset(), r.returnRate(), r.benchmarkReturn(), r.maxDrawdown(),
                r.winRate(), r.profitFactor(), r.avgHoldingDays());
        SeasonFinishResponse response = toResponse(resultRepository.save(result));
        /* 배지는 여기서 준다 — 끝낸 자리에서 바로 보여야 하고, finish 는 끝난 회차를 다시 계산하지
           않으므로 이 지점을 놓치면 그 회차의 배지를 줄 기회가 없다. 승률의 분모와 같은 수를
           넘겨야 해 매도만 센다. */
        long sells = trades.stream().filter(t -> t.getSide() == SeasonTrade.Side.SELL).count();
        badgeService.award(me.getUser(), season, r, (int) sells);
        return new Finished(response, reviewService.hasKey());
    }

    /**
     * 복기를 뒤따라 만든다. 같은 회차가 이미 줄에 있으면(결과 화면이 finish 를 두 번 불렀거나 "다시
     * 만들기" 를 연타) 더 넣지 않는다 — {@link #reviewing} 이 그 표시이고 {@link #result} 가 이 표시를
     * {@code reviewPending} 으로 내린다. 트랜잭션은 둘로 나눈다 — GMS 호출이 수십 초라 그동안 DB
     * 커넥션을 잡고 있으면 안 된다. ① 읽기 — 저장된 지표·체결로 프롬프트 재료 ② 트랜잭션 밖 — GMS
     * ③ 쓰기 — 아직 비어 있으면 채운다.
     * ponytail: 진행 표시가 메모리라 인스턴스가 하나일 때만 맞다. 여럿이면 season_results 에 상태 칸을 둔다.
     */
    private void scheduleReview(Long participantId) {
        if (!reviewing.add(participantId)) {
            return;
        }
        reviewExecutor.execute(() -> {
            try {
                generateReview(participantId);
            } catch (RuntimeException e) {
                log.warn("[SEASON-09] 복기 생성 실패 participant={} — {}", participantId, e.toString());
            } finally {
                reviewing.remove(participantId);
            }
        });
    }

    private void generateReview(Long participantId) {
        String input = tx.execute(status -> resultRepository.findById(participantId)
                .filter(saved -> saved.getReviewBody() == null)
                .map(saved -> reviewInput(
                        saved.getParticipant().getSeason(),
                        tradeRepository.findByParticipant_IdOrderByIdAsc(participantId),
                        toResult(saved)))
                .orElse(null));
        if (input == null) {
            return;
        }
        String review = reviewService.review(input);
        if (review == null) {
            return;
        }
        tx.executeWithoutResult(status -> resultRepository.findById(participantId)
                .filter(saved -> saved.getReviewBody() == null)
                .ifPresent(saved -> saved.review(review, reviewService.promptVersion())));
    }

    /**
     * 복기 재료 — 성적표·체결에 <b>시장 쪽 재료</b>를 얹는다(2026-09-19). 시즌 전 종목의 종가(게임일 1 ~
     * 마지막 날)와 게임일별 시장 요약(season_news 의 종목 없는 행)을 읽는다. 200종목 × 40여 일이라
     * 만 행 안쪽이고 회차당 한 번이다. 끝난 회차의 재료라 진행일 상한을 걸지 않는다.
     */
    private String reviewInput(Season season, List<SeasonTrade> trades, SeasonResultCalculator.Result result) {
        int lastDay = season.getLengthDays();
        Map<Long, SeasonTicker> tickers = new HashMap<>();
        for (SeasonTicker t : seasonTickerRepository.findBySeason_IdOrderByDisplayNameAsc(season.getId())) {
            tickers.put(t.getId(), t);
        }
        List<SeasonMarketDigest.Quote> quotes = tickers.isEmpty() ? List.of()
                : seasonPriceRepository
                        .findByTicker_IdInAndGameDayBetweenOrderByGameDayAsc(tickers.keySet(), 1, lastDay)
                        .stream()
                        .map(p -> {
                            SeasonTicker t = tickers.get(p.getTicker().getId());
                            return new SeasonMarketDigest.Quote(
                                    t.getId(), t.getDisplayName(), t.getSector(), p.getGameDay(), p.getClose());
                        })
                        .toList();
        List<SeasonNews> hints = new ArrayList<>(newsRepository
                .findBySeason_IdAndTickerIsNullAndGameDayLessThanEqualOrderByGameDayDescIdAsc(
                        season.getId(), lastDay, Limit.of(SeasonReviewService.MAX_HINTS)));
        hints.sort(Comparator.comparingInt(SeasonNews::getGameDay));
        return SeasonReviewService.input(
                season, trades, result, SeasonMarketDigest.of(quotes, lastDay), hints);
    }

    /** 성과 지표 — 체결과 거래한 종목의 종가에서 계산한다. */
    private SeasonResultCalculator.Result compute(Season season, List<SeasonTrade> trades) {
        int lastDay = season.getLengthDays();
        List<Long> traded = trades.stream().map(tr -> tr.getTicker().getId()).distinct().toList();
        Map<Long, TreeMap<Integer, BigDecimal>> closes = new HashMap<>();
        if (!traded.isEmpty()) {
            for (SeasonPrice p : seasonPriceRepository
                    .findByTicker_IdInAndGameDayBetweenOrderByGameDayAsc(traded, 1, lastDay)) {
                closes.computeIfAbsent(p.getTicker().getId(), k -> new TreeMap<>())
                        .put(p.getGameDay(), p.getClose());
            }
        }
        return SeasonResultCalculator.compute(
                season.getInitialCash(),
                lastDay,
                trades,
                (tickerId, day) -> {
                    Map.Entry<Integer, BigDecimal> e =
                            closes.getOrDefault(tickerId, new TreeMap<>()).floorEntry(day);
                    if (e == null) {
                        throw new BusinessException(ErrorCode.SEASON_PRICE_NOT_FOUND);
                    }
                    return e.getValue();
                },
                closesOf(season.getId(), 1),
                closesOf(season.getId(), lastDay));
    }

    private static SeasonResultCalculator.Result toResult(SeasonResult s) {
        return new SeasonResultCalculator.Result(
                s.getFinalAsset(), s.getReturnRate(), s.getBenchmarkReturn(), s.getMaxDrawdown(),
                s.getWinRate(), s.getProfitFactor(), s.getAvgHoldingDays());
    }

    /** 내 마지막 회차의 결과. 끝나지 않았으면 404 — 결과는 finish 가 만든다. */
    @Transactional(readOnly = true)
    public SeasonResultResponse result(Long userId, Long seasonId) {
        SeasonParticipant me = myAttempt(userId, seasonId);
        SeasonResult r = resultRepository.findById(me.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_RESULT_NOT_FOUND));
        return new SeasonResultResponse(
                r.getParticipantId(), r.getFinalAsset(), r.getReturnRate(), r.getBenchmarkReturn(),
                r.getMaxDrawdown(), r.getWinRate(), r.getProfitFactor(), r.getAvgHoldingDays(),
                r.getScore(), r.getGrade(), r.getReviewBody(), reviewing.contains(r.getParticipantId()),
                r.getClosedAt());
    }

    private Map<Long, BigDecimal> closesOf(Long seasonId, int gameDay) {
        return seasonPriceRepository.findByTicker_Season_IdAndGameDay(seasonId, gameDay).stream()
                .collect(Collectors.toMap(p -> p.getTicker().getId(), SeasonPrice::getClose));
    }

    private static SeasonFinishResponse toResponse(SeasonResult r) {
        return new SeasonFinishResponse(
                r.getParticipantId(), r.getFinalAsset(), r.getReturnRate(), r.getBenchmarkReturn(),
                r.getMaxDrawdown(), r.getWinRate(), r.getProfitFactor(), r.getAvgHoldingDays());
    }

    /** 주문·진행용 — 내 마지막 회차를 잠그고 읽는다. 끝난 회차면 409. */
    private SeasonParticipant lockedOngoing(Long userId, Long seasonId) {
        SeasonParticipant me = participantRepository
                .lockById(myAttempt(userId, seasonId).getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_NOT_JOINED));
        if (!me.isOngoing()) {
            throw new BusinessException(ErrorCode.SEASON_ATTEMPT_ENDED);
        }
        return me;
    }

    /** 볼 수 있는 시즌의 내 마지막 회차. 참가한 적이 없으면 409 다. */
    private SeasonParticipant myAttempt(Long userId, Long seasonId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        Season season = seasonRepository.findById(seasonId)
                .filter(s -> s.getMode() != Season.Mode.DEMO || user.getRole() == User.Role.ADMIN)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_NOT_FOUND));
        return participantRepository
                .findFirstBySeason_IdAndUser_IdOrderByAttemptNoDesc(season.getId(), userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEASON_NOT_JOINED));
    }

    private static BigDecimal valueOf(SeasonPosition p, Map<Long, BigDecimal> closes) {
        BigDecimal close = closes.get(p.getTicker().getId());
        if (close == null) {
            throw new BusinessException(ErrorCode.SEASON_PRICE_NOT_FOUND);
        }
        return close.multiply(BigDecimal.valueOf(p.getQty()));
    }

    /** part / whole × 100, 소수 둘째 자리. whole 이 0 이면 0 이다. */
    private static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
    }

    private static int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
