package ssafy.a507.backend.domain.chain.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.EventSettlementRunRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventStockRepository;
import ssafy.a507.backend.domain.market.entity.DailyQuote;
import ssafy.a507.backend.domain.market.repository.DailyQuoteRepository;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionCommit;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;

/**
 * 정산 러너의 DB 쪽 (ANT-EVENT-03). 메서드 하나가 트랜잭션 하나 — 러너는 체인을 기다리는 동안 DB 트랜잭션을 잡지 않는다
 * ({@code AnchorRunner · AnchorBatchService} 와 같은 나눔).
 *
 * <p>여기서 <b>읽기만</b> 한다(전제 검사 · 리빌 재료 · 정산 후보). 배팅·이벤트·종목의 상태는 인덱서 ③ 이 로그로 쓴다 — 러너가 같은
 * 컬럼을 쓰면 "누가 진실인가" 가 둘이 된다. 러너가 쓰는 유일한 행은 실행 기록({@link EventSettlementRun})이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventSettlementService {

    private final PredictionEventRepository events;
    private final PredictionEventStockRepository stocks;
    private final PredictionEventBetRepository bets;
    private final PredictionCommitRepository commits;
    private final DailyQuoteRepository quotes;
    private final EventSettlementRunRepository runs;

    /** 종목 한 칸의 판정 재료. 가격은 ×100 정수(컨트랙트 단위). */
    public record StockPrice(int idx, String code, BigInteger basePrice, BigInteger closePrice) {}

    /** 전제 검사 결과. {@code reasons} 가 비어 있어야 정산할 수 있다. */
    public record Preflight(
            long eventId, PredictionEvent.Status dbStatus, int stockCount, List<StockPrice> stockPrices, List<String> reasons) {
        public boolean ready() {
            return reasons.isEmpty();
        }
    }

    /** 리빌 한 건의 인자. noteHash 가 없으면(커밋 유실) 리빌하지 않고 환불로 보낸다. */
    public record RevealItem(long betIndex, Prediction.Direction side, String noteHash) {}

    /**
     * 전제 검사 (지라 -256 ①). SETTLED 면 reasons 에 넣지 않고 dbStatus 로 알린다 — "이미 끝남" 은 실패가 아니다.
     * 종가 규칙은 판정 배치와 같다: {@code settle_date} 이후 첫 거래일({@code closeOnOrAfter}).
     */
    @Transactional(readOnly = true)
    public Preflight preflight(long eventId) {
        PredictionEvent event = events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        List<String> reasons = new ArrayList<>();
        PredictionEvent.Status status = event.getStatus();
        if (status == PredictionEvent.Status.SCHEDULED || status == PredictionEvent.Status.OPEN) {
            reasons.add("이벤트가 아직 봉인되지 않았다(status=" + status + ")");
        }

        List<PredictionEventStock> eventStocks = stocks.findCardsByEventId(eventId);
        List<StockPrice> prices = new ArrayList<>(eventStocks.size());
        for (PredictionEventStock s : eventStocks) {
            String code = s.getStock().getCode();
            if (s.getBasePrice() == null) {
                reasons.add("종목 " + s.getIdx() + "(" + code + ") 기준가 없음");
                continue;
            }
            Optional<DailyQuote> close = quotes.findFirstByStock_CodeAndTradeDateGreaterThanEqualOrderByTradeDateAsc(code, event.getSettleDate());
            if (close.isEmpty()) {
                reasons.add("종목 " + s.getIdx() + "(" + code + ") " + event.getSettleDate() + " 이후 종가 없음");
                continue;
            }
            prices.add(new StockPrice(s.getIdx(), code, toChainPrice(s.getBasePrice()), toChainPrice(close.get().getClose())));
        }
        if (eventStocks.isEmpty()) {
            reasons.add("종목이 없다");
        }

        long unsettledPredictions =
                bets.findOnChainByStatus(eventId, List.of(PredictionEventBet.Status.ACTIVE, PredictionEventBet.Status.REVEALED)).stream()
                        .map(PredictionEventBet::getPrediction)
                        .filter(p -> p.getStatus() != Prediction.Status.HIT && p.getStatus() != Prediction.Status.MISS)
                        .count();
        if (unsettledPredictions > 0) {
            reasons.add("판정(HIT/MISS)이 안 끝난 참가 예측 " + unsettledPredictions + "건 — 13:30 판정 배치 뒤에");
        }
        return new Preflight(eventId, status, eventStocks.size(), prices, reasons);
    }

    /**
     * 리빌 재료 — 체인에 올라갔고(bet_index) 아직 ACTIVE 인 배팅. 방향은 예측, noteHash 는 예측 커밋에서.
     * 컨트랙트가 같은 식({@code keccak(eventId, idx, side, noteHash)})으로 대조하므로 이 셋이 등록 때와 바이트 단위로 같아야 한다.
     */
    @Transactional(readOnly = true)
    public List<RevealItem> revealItems(long eventId) {
        List<PredictionEventBet> candidates = bets.findOnChainByStatus(eventId, List.of(PredictionEventBet.Status.ACTIVE));
        List<Long> ids = candidates.stream().map(PredictionEventBet::getPredictionId).toList();
        Map<Long, PredictionCommit> byPrediction =
                commits.findAllById(ids).stream().collect(Collectors.toMap(PredictionCommit::getPredictionId, Function.identity()));
        List<RevealItem> out = new ArrayList<>(candidates.size());
        for (PredictionEventBet b : candidates) {
            PredictionCommit c = byPrediction.get(b.getPredictionId());
            String noteHash = c == null ? null : c.getNoteHash();
            if (noteHash == null) {
                log.warn("배팅(prediction {}) — 예측 커밋의 noteHash 가 없다. 리빌하지 않는다 → 정산에서 원금 환불", b.getPredictionId());
            }
            out.add(new RevealItem(b.getBetIndex(), b.getPrediction().getDirection(), noteHash));
        }
        return out;
    }

    /** 정산 후보 — 체인에 올라간 ACTIVE·REVEALED 배팅의 온체인 betId. 미리빌(ACTIVE)도 넣는다: settle 이 환불한다. */
    @Transactional(readOnly = true)
    public List<Long> settleCandidates(long eventId) {
        return bets.findOnChainByStatus(eventId, List.of(PredictionEventBet.Status.ACTIVE, PredictionEventBet.Status.REVEALED)).stream()
                .map(PredictionEventBet::getBetIndex)
                .toList();
    }

    // ── 실행 기록 ─────────────────────────────────────────────────────────

    /**
     * 새 실행 행. 끝을 못 본 옛 실행(서버 크래시)이 남아 있으면 INTERRUPTED 로 닫는다 — 동시 실행은 러너의 메모리 잠금이 막으므로
     * 여기 도달했다는 건 그 실행이 정말로 죽은 것이다.
     */
    @Transactional
    public long startRun(long eventId) {
        PredictionEvent event = events.findById(eventId).orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND));
        for (EventSettlementRun stale : runs.findUnfinished(eventId)) {
            stale.fail(EventSettlementRun.INTERRUPTED, "끝을 못 보고 남은 실행 — 서버 재시작. 다음 실행이 온체인 상태로 이어 간다");
            log.warn("이벤트 {} 정산 실행 #{} — INTERRUPTED 로 닫음", eventId, stale.getId());
        }
        return runs.save(EventSettlementRun.start(event)).getId();
    }

    @Transactional
    public void progress(long runId, EventSettlementRun.Phase phase, int done, int total) {
        runs.findById(runId).ifPresent(r -> r.progress(phase, done, total));
    }

    @Transactional
    public void finish(long runId) {
        runs.findById(runId).ifPresent(EventSettlementRun::finish);
    }

    @Transactional
    public void fail(long runId, String code, String message) {
        runs.findById(runId).ifPresent(r -> r.fail(code, message));
    }

    @Transactional(readOnly = true)
    public Optional<EventSettlementRun> latestRun(long eventId) {
        return runs.findLatest(eventId);
    }

    /** {@code numeric(14,2)} → 컨트랙트 가격(×100 정수). 소수 둘째 자리까지라 정확히 떨어진다. */
    static BigInteger toChainPrice(BigDecimal price) {
        return price.setScale(2, java.math.RoundingMode.HALF_UP).unscaledValue();
    }
}
