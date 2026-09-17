package ssafy.a507.backend.domain.event.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Me;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Mine;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Pool;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Result;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Rules;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.StockCard;
import ssafy.a507.backend.domain.event.dto.EventListResponse;
import ssafy.a507.backend.domain.event.dto.EventMeResponse;
import ssafy.a507.backend.domain.event.dto.EventPhase;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventStockRepository;
import ssafy.a507.backend.domain.market.repository.DailyQuoteRepository;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 이벤트 조회 — ANT-EVENT-04·06, 화면 J-01. 작업명세 v1.2 §3-1·2·4 · §4.
 *
 * <p><b>남의 방향·목표가는 나가지 않는다.</b> 종목 카드는 집계(풀·건수·배당률)만 싣고, 개별 값은
 * {@code mine} 에만 담는다 — §1 "비공개" 규칙이다. 그래서 집계 쿼리가 방향별 합계만 읽고 행을
 * 끌어오지 않는다.
 *
 * <p>카드 10장을 채우는 데 쿼리는 <b>넷</b>이다 — 이벤트 · 종목(+stock 페치) · 배팅 집계(이벤트 단위
 * 한 번) · 내 배팅(+prediction 페치). 카드마다 물으면 한 장에 열 번이 된다.
 */
@Service
@RequiredArgsConstructor
public class EventQueryService {

    /** 배당률 소수 자릿수. 명세 §4 "소수 둘째 자리" */
    private static final int ODDS_SCALE = 2;

    private final PredictionEventRepository events;
    private final PredictionEventStockRepository eventStocks;
    private final PredictionEventBetRepository bets;
    private final PredictionEventResultRepository results;
    private final DailyQuoteRepository dailyQuotes;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public EventListResponse list() {
        return new EventListResponse(
                events.findAllByOrderByIdDesc().stream().map(EventListResponse.Item::of).toList());
    }

    @Transactional(readOnly = true)
    public EventHomeResponse home(Long eventId) {
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));

        List<PredictionEventStock> cards = eventStocks.findCardsByEventId(eventId);
        Map<Long, Map<Prediction.Direction, Agg>> pools = poolsOf(eventId);
        Map<String, BigDecimal> lastCloses = lastClosesOf(cards);

        Long viewerId = currentUserProvider.currentUserId();
        Map<Long, PredictionEventBet> mine = mineByStockId(eventId, viewerId);

        List<StockCard> stocks = new ArrayList<>(cards.size());
        for (PredictionEventStock card : cards) {
            PredictionEventBet bet = mine.get(card.getId());
            stocks.add(new StockCard(
                    card.getIdx(),
                    card.getStock().getCode(),
                    card.getStock().getName(),
                    card.getBasePrice(),
                    lastCloses.get(card.getStock().getCode()),
                    poolOf(pools.get(card.getId())),
                    bet == null ? null : Mine.of(bet),
                    resultOf(card, bet)));
        }

        return new EventHomeResponse(
                event.getId(),
                event.getSlug(),
                event.getTitle(),
                event.getStatus(),
                EventPhase.of(event.getStatus()),
                event.getOpensAt(),
                event.getSealsAt(),
                event.getBaseDate(),
                event.getSettleDate(),
                new Rules(event.getMinStake(), event.getMaxStake(), event.getMinStocks(), cards.size()),
                stocks,
                meOf(event, mine.values(), cards.size(), rankOf(eventId, viewerId)));
    }

    /** 한 종목·한 방향의 합계와 건수 */
    private record Agg(BigInteger sum, long count) {}

    private Map<Long, Map<Prediction.Direction, Agg>> poolsOf(Long eventId) {
        Map<Long, Map<Prediction.Direction, Agg>> byStock = new HashMap<>();
        for (Object[] row : bets.sumStakeByStockAndDirection(eventId)) {
            Long stockId = (Long) row[0];
            Prediction.Direction direction = (Prediction.Direction) row[1];
            BigInteger sum = toBigInteger(row[2]);
            long count = ((Number) row[3]).longValue();
            byStock.computeIfAbsent(stockId, k -> new EnumMap<>(Prediction.Direction.class))
                    .put(direction, new Agg(sum, count));
        }
        return byStock;
    }

    /**
     * 배당률 = (전체 풀) / (그 방향 풀). <b>해당 풀이 0 이면 null</b> — 0 으로 나눌 수 없고, 0 배당은
     * "걸면 아무것도 못 받는다"는 거짓이다. 화면은 null 을 대시로 그린다.
     */
    private Pool poolOf(Map<Prediction.Direction, Agg> byDirection) {
        Agg up = byDirection == null ? null : byDirection.get(Prediction.Direction.UP);
        Agg down = byDirection == null ? null : byDirection.get(Prediction.Direction.DOWN);
        BigInteger upSum = up == null ? BigInteger.ZERO : up.sum();
        BigInteger downSum = down == null ? BigInteger.ZERO : down.sum();
        BigInteger total = upSum.add(downSum);
        return new Pool(
                upSum,
                downSum,
                up == null ? 0 : up.count(),
                down == null ? 0 : down.count(),
                odds(total, upSum),
                odds(total, downSum));
    }

    private static BigDecimal odds(BigInteger total, BigInteger side) {
        if (side.signum() <= 0) {
            return null;
        }
        return new BigDecimal(total).divide(new BigDecimal(side), ODDS_SCALE, RoundingMode.HALF_UP);
    }

    private Map<Long, PredictionEventBet> mineByStockId(Long eventId, Long viewerId) {
        if (viewerId == null) {
            return Map.of();
        }
        Map<Long, PredictionEventBet> byStock = new HashMap<>();
        for (PredictionEventBet bet : bets.findMine(eventId, viewerId)) {
            byStock.put(bet.getEventStock().getId(), bet);
        }
        return byStock;
    }

    /** 정산 전에는 null 이다 — 판정가·승패가 아직 없고, 0 으로 내리면 "졌다"로 읽힌다. */
    private Result resultOf(PredictionEventStock card, PredictionEventBet bet) {
        if (card.getWinner() == null) {
            return null;
        }
        return new Result(card.getClosePrice(), card.getWinner(), bet == null ? null : bet.getPayout());
    }

    /**
     * 내 참가 요약. {@code paid}·{@code net} 은 정산 전 null 이다 — 0 은 "본전"으로 읽힌다.
     *
     * <p>{@code rank} 는 정산 뒤 굳힌 표에서 온다. 그전에는 null 이다 — 등수를 만들어 내지 않는다.
     */
    private Me meOf(
            PredictionEvent event,
            java.util.Collection<PredictionEventBet> mine,
            int stockCountInEvent,
            Integer rank) {
        int stockCount = mine.size();
        BigInteger staked = BigInteger.ZERO;
        BigInteger paid = BigInteger.ZERO;
        boolean anyPaid = false;
        for (PredictionEventBet bet : mine) {
            staked = staked.add(bet.getStake());
            if (bet.getPayout() != null) {
                paid = paid.add(bet.getPayout());
                anyPaid = true;
            }
        }
        return new Me(
                stockCount,
                staked,
                stockCount >= event.getMinStocks(),
                // 더 걸 수 있는 종목 수. 이벤트 종목이 10개고 6개를 걸었으면 4다.
                Math.max(0, stockCountInEvent - stockCount),
                anyPaid ? paid : null,
                anyPaid ? paid.subtract(staked) : null,
                rank);
    }

    /**
     * 굳힌 순위. 정산 전에는 행이 없어 null 이다 — 그때 등수를 만들어 내지 않는다(§4, 09-16 결정).
     * 자격 미달이면 {@code rank} 자체가 null 로 굳어 있다.
     */
    private Integer rankOf(Long eventId, Long viewerId) {
        if (viewerId == null) {
            return null;
        }
        return results.findByEventIdAndUserId(eventId, viewerId)
                .map(PredictionEventResult::getRank)
                .orElse(null);
    }

    /**
     * 내 배팅 — 화면 J-01 의 "내 참가 내역" 탭 (ANT-EVENT-06, §4).
     *
     * <p>홈과 겹쳐 보이지만 주인공이 다르다. 홈은 종목 카드가 주인공이라 내 배팅이 카드에 딸린 값으로
     * 들어가고, 여기는 내가 건 것만 모아 tx 해시·작업 id·오차까지 준다.
     *
     * <p>쿼리는 둘이다 — 배팅(+예측·종목 페치) · 굳힌 순위 한 줄. 종목·예측을 페치하지 않으면
     * {@code open-in-view: false} 라 DTO 를 만들 때 지연 로딩이 터진다.
     */
    @Transactional(readOnly = true)
    public EventMeResponse me(Long eventId, Long viewerId) {
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));

        List<PredictionEventBet> mine = bets.findMineDetailed(eventId, viewerId);
        List<EventMeResponse.Entry> entries = new ArrayList<>(mine.size());
        BigInteger staked = BigInteger.ZERO;
        BigInteger paid = BigInteger.ZERO;
        boolean anyPaid = false;
        for (PredictionEventBet bet : mine) {
            entries.add(EventMeResponse.Entry.of(bet));
            staked = staked.add(bet.getStake());
            if (bet.getPayout() != null) {
                paid = paid.add(bet.getPayout());
                anyPaid = true;
            }
        }

        PredictionEventResult frozen =
                results.findByEventIdAndUserId(eventId, viewerId).orElse(null);
        return new EventMeResponse(
                EventPhase.of(event.getStatus()),
                new EventMeResponse.Summary(
                        mine.size(),
                        staked,
                        mine.size() >= event.getMinStocks(),
                        anyPaid ? paid : null,
                        anyPaid ? paid.subtract(staked) : null,
                        // 평균 오차는 굳힌 표에서 읽는다 — HIT 만 세는 규칙이 거기 한 곳에 있다(§8).
                        frozen == null ? null : frozen.getAvgError(),
                        frozen == null ? null : frozen.getRank()),
                entries);
    }

    /**
     * 표시용 최신 종가. 판정에는 쓰지 않는다 — 판정가는 정산 러너가 {@code settle_date} 종가로 채운다.
     *
     * <p>카드 10장을 쿼리 한 번으로 읽는다. 시세가 없는 종목은 맵에 키가 없어 {@code get} 이 null 이 된다.
     */
    private Map<String, BigDecimal> lastClosesOf(List<PredictionEventStock> cards) {
        if (cards.isEmpty()) {
            return Map.of();
        }
        List<String> codes = cards.stream().map(c -> c.getStock().getCode()).toList();
        Map<String, BigDecimal> byCode = new HashMap<>();
        for (Object[] row : dailyQuotes.findLatestCloseByCodes(codes)) {
            byCode.put((String) row[0], (BigDecimal) row[1]);
        }
        return byCode;
    }

    private static BigInteger toBigInteger(Object value) {
        if (value == null) {
            return BigInteger.ZERO;
        }
        if (value instanceof BigInteger bi) {
            return bi;
        }
        if (value instanceof BigDecimal bd) {
            return bd.toBigInteger();
        }
        return BigInteger.valueOf(((Number) value).longValue());
    }
}
