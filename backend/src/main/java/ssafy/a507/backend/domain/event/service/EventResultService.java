package ssafy.a507.backend.domain.event.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 회차 순위 굳힘 (ANT-EVENT-03 · REST 명세 §8).
 *
 * <p>정산 러너가 끝난 뒤 REST 의 정산 엔드포인트가 부른다({@code runner.run(id)} → {@code freeze(id)}, 09-16 결정 — 러너(chain)가
 * 이벤트 서비스를 부르면 패키지 방향이 거꾸로라 밖에서 잇는다). 집계는 SQL 의 {@code FILTER} 대신 자바에서 한다 — H2 와 PG 에서
 * 정렬·NULL 순서가 갈리는 걸 피하고, 기존 {@code RankingSnapshotService} 도 순위를 자바에서 매긴다.
 *
 * <p>멱등: 그 이벤트의 결과 행을 지우고 다시 쓴다. 정산이 안 끝난 배팅(ACTIVE·REVEALED)이 하나라도 있으면 굳히지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventResultService {

    private final PredictionEventRepository events;
    private final PredictionEventBetRepository bets;
    private final PredictionEventResultRepository results;

    /** 회원 한 사람의 집계. 정렬 키가 전부 여기 있다. */
    record Row(Long userId, int stockCount, int hitCount, BigInteger staked, BigInteger paid, BigDecimal avgError, Instant firstAt) {
        BigInteger net() {
            return paid.subtract(staked);
        }
    }

    /**
     * @return 쓴 행 수(참가자 수)
     * @throws BusinessException EVENT_NOT_FOUND · EVENT_NOT_READY(정산 안 끝난 배팅이 있음)
     */
    @Transactional
    public int freeze(long eventId) {
        PredictionEvent event = events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        List<PredictionEventBet> all = bets.findAllForResults(eventId);

        long unsettled =
                all.stream()
                        .filter(b -> b.getBetStatus() == PredictionEventBet.Status.ACTIVE || b.getBetStatus() == PredictionEventBet.Status.REVEALED)
                        .count();
        if (unsettled > 0) {
            throw new BusinessException(ErrorCode.EVENT_NOT_READY);
        }

        Map<Long, List<PredictionEventBet>> byUser = new LinkedHashMap<>();
        for (PredictionEventBet b : all) {
            // 체인에 못 올라간 배팅(FAILED · PENDING)은 돈이 안 움직였다 — 집계 밖, 종목 수에도 안 센다.
            if (b.getBetStatus() != PredictionEventBet.Status.SETTLED && b.getBetStatus() != PredictionEventBet.Status.REFUNDED) {
                continue;
            }
            byUser.computeIfAbsent(b.getUser().getId(), k -> new ArrayList<>()).add(b);
        }

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Long, List<PredictionEventBet>> e : byUser.entrySet()) {
            rows.add(aggregate(e.getKey(), e.getValue()));
        }
        rows.sort(
                Comparator.comparing(Row::net, Comparator.reverseOrder())
                        .thenComparing(Row::avgError, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Row::firstAt)
                        .thenComparing(Row::userId));

        results.deleteByEventId(eventId);
        int rank = 0;
        List<PredictionEventResult> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            boolean qualified = r.stockCount() >= event.getMinStocks();
            Integer rankOrNull = qualified ? ++rank : null;
            out.add(
                    PredictionEventResult.of(
                            eventId, r.userId(), r.stockCount(), r.hitCount(), r.staked(), r.paid(), r.avgError(), qualified, rankOrNull));
        }
        results.saveAll(out);
        log.info("이벤트 {} 순위 굳힘 — 참가자 {}명, 자격자 {}명", eventId, out.size(), rank);
        return out.size();
    }

    private static Row aggregate(Long userId, List<PredictionEventBet> list) {
        BigInteger staked = BigInteger.ZERO;
        BigInteger paid = BigInteger.ZERO;
        int hit = 0;
        BigDecimal errorSum = BigDecimal.ZERO;
        int errorCount = 0;
        Instant firstAt = null;
        for (PredictionEventBet b : list) {
            staked = staked.add(b.getStake());
            paid = paid.add(b.getPayout() == null ? BigInteger.ZERO : b.getPayout());
            Prediction p = b.getPrediction();
            if (p.getStatus() == Prediction.Status.HIT) {
                hit++;
                if (p.getErrorRate() != null) {
                    errorSum = errorSum.add(p.getErrorRate());
                    errorCount++;
                }
            }
            if (firstAt == null || b.getCreatedAt().isBefore(firstAt)) {
                firstAt = b.getCreatedAt();
            }
        }
        BigDecimal avg = errorCount == 0 ? null : errorSum.divide(BigDecimal.valueOf(errorCount), 3, RoundingMode.HALF_UP);
        return new Row(userId, list.size(), hit, staked, paid, avg, firstAt);
    }
}
