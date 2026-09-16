package ssafy.a507.backend.domain.chain.indexer;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.entity.ChainEvent;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.repository.ChainEventRepository;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.repository.TokenLedgerRepository;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventStockRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * 풀 인덱서 ③ 의 DB 쪽 (ANT-EVENT-02). 메서드 하나가 트랜잭션 하나다 — {@link TokenIndexService} 와 같은 구조.
 *
 * <p>이벤트 한 건이 남기는 것(plan §⑦ 표):
 * <pre>
 * EventOpened    → prediction_events.open_tx_hash                         · 작업(EVENT_ADMIN) SUCCEEDED
 * BetPlaced      → bets ACTIVE · bet_index · tx  · token_ledger −stake EVENT_STAKE · 작업(EVENT_BET) SUCCEEDED
 * EventSealed    → prediction_events SEALED · seal_tx_hash                 · 작업(EVENT_ADMIN) SUCCEEDED
 * BetRevealed    → bets REVEALED · reveal_tx
 * EventRevealed  → prediction_events REVEALED
 * StockResolved  → stocks close_price · winner · resolve_tx
 * EventResolved  → prediction_events RESOLVED
 * BetSettled     → bets SETTLED · payout · settle_tx · token_ledger +payout EVENT_PAYOUT (0 이면 원장 생략)
 * BetRefunded    → bets REFUNDED · payout=stake · settle_tx · token_ledger +stake EVENT_REFUND
 * EventSettled   → prediction_events SETTLED
 * </pre>
 * 전부 한 트랜잭션이라 중간에 터지면 이벤트도 안 남고 다음 회차가 다시 받는다.
 *
 * <p><b>체인이 진실이다.</b> 짝 되는 DB 행(이벤트·종목·배팅)이 없어도 이벤트는 저장하고 원장은 쓴다 — 회원 지갑의 돈이 움직인
 * 사실은 DB 행 유무와 무관하다. 행이 없는 건 ERROR 로 남기고 사람이 본다(수동 tx · DB 유실). 자동 환불은 없다(plan §3-⑥ 그대로).
 *
 * <p>이벤트가 <b>안 오는</b> 실패(SEND_LOST · REVERTED · TX_DROPPED)도 여기서 닫는다({@link #findStalePending} → {@link #failOperation}) —
 * 단, <b>자기 작업(EVENT_BET · EVENT_ADMIN)만</b>. 토큰 인덱서 ⑤ 도 kind 를 안 가리고 같은 작업을 볼 수 있지만, 이벤트 흐름이 토큰
 * 인덱서에 기대지 않으려고(09-16 결정) 풀이 스스로 닫는다. 둘이 겹쳐도 "PENDING 일 때만 닫는다" 라 두 번 닫히지 않는다.
 * 작업을 닫을 때 배팅도 같은 트랜잭션에서 FAILED 로 맞추고, 남이 먼저 닫은 작업은 {@link #reconcileFailedBets} 가 따라 적는다.
 */
@Slf4j
@Service
public class PoolIndexService {

    private final ChainEventRepository events;
    private final OperationRepository operations;
    private final TokenLedgerRepository ledger;
    private final UserRepository users;
    private final PredictionEventRepository predictionEvents;
    private final PredictionEventStockRepository stocks;
    private final PredictionEventBetRepository bets;
    /** Boot 4 = Jackson 3. 웹 계층 매퍼 빈에 기대지 않고 여기서 만든다({@code TokenIndexService} 와 같은 이유). */
    private final JsonMapper json = JsonMapper.builder().build();

    public PoolIndexService(
            ChainEventRepository events,
            OperationRepository operations,
            TokenLedgerRepository ledger,
            UserRepository users,
            PredictionEventRepository predictionEvents,
            PredictionEventStockRepository stocks,
            PredictionEventBetRepository bets) {
        this.events = events;
        this.operations = operations;
        this.ledger = ledger;
        this.users = users;
        this.predictionEvents = predictionEvents;
        this.stocks = stocks;
        this.bets = bets;
    }

    /**
     * {@code chain_events.payload}. 이벤트 인자 전부 + reorg 검사용 블록 해시. 큰 정수는 문자열 — numeric(30,0) 을 JSON 숫자로
     * 왕복시키지 않으려는 것. 안 쓰는 칸은 null.
     */
    public record Payload(
            String kind,
            String eventId,
            String betId,
            String user,
            Integer stockIdx,
            String amount,
            String commitHex,
            Integer side,
            String basePrice,
            String closePrice,
            Integer winner,
            Integer stockCount,
            String minStake,
            String maxStake,
            String blockHash) {}

    public record LastEvent(long blockNumber, String blockHash) {}

    /** 정리 대상 PENDING 작업. 체인 조회(receipt)는 {@link PoolIndexer} 가 트랜잭션 밖에서 한다. */
    public record StalePending(String operationId, String txHash, Instant createdAt) {}

    /** 풀 인덱서가 닫는 작업 종류. 나머지 kind 는 토큰 인덱서 ⑤ 의 몫이다. */
    static final List<Operation.Kind> POOL_KINDS = List.of(Operation.Kind.EVENT_BET, Operation.Kind.EVENT_ADMIN);

    /** 로그 하나를 적재하고 반영한다. 이미 있는 (tx_hash, log_index) 면 아무것도 하지 않는다. @return 새로 적재했으면 true */
    @Transactional
    public boolean record(PoolLog log, Instant now) {
        if (events.existsByTxHashAndLogIndex(log.txHash(), log.logIndex())) {
            return false;
        }
        Payload payload =
                new Payload(
                        log.kind().name(),
                        Long.toString(log.eventId()),
                        log.betId() == null ? null : Long.toString(log.betId()),
                        log.user(),
                        log.stockIdx(),
                        str(log.amount()),
                        log.commitHex(),
                        log.side(),
                        str(log.basePrice()),
                        str(log.closePrice()),
                        log.winner(),
                        log.stockCount(),
                        str(log.minStake()),
                        str(log.maxStake()),
                        log.blockHash());
        ChainEvent event =
                ChainEvent.record(
                        log.txHash(),
                        log.logIndex(),
                        log.contractAddress(),
                        log.kind().eventName,
                        log.blockNumber(),
                        json.writeValueAsString(payload));
        events.save(event);
        apply(event, payload);
        event.markProcessed(now);
        return true;
    }

    /** {@code processed_at IS NULL} 인 풀 이벤트를 payload 로 다시 반영한다. 토큰·앵커 이벤트는 각자 서비스 소관. */
    @Transactional
    public void reprocess(long eventId, Instant now) {
        ChainEvent event = events.findById(eventId).orElseThrow();
        if (event.isProcessed() || PoolLog.Kind.ofEventName(event.getEventName()).isEmpty()) {
            return;
        }
        Payload payload = json.readValue(event.getPayload(), Payload.class);
        apply(event, payload);
        event.markProcessed(now);
    }

    @Transactional(readOnly = true)
    public List<Long> findUnprocessedIds() {
        List<Long> ids = new ArrayList<>();
        for (ChainEvent e : events.findByProcessedAtIsNullOrderByBlockNumberAscLogIndexAsc()) {
            if (PoolLog.Kind.ofEventName(e.getEventName()).isPresent()) {
                ids.add(e.getId());
            }
        }
        return ids;
    }

    /** 재시작 커서 = 풀 컨트랙트 이벤트의 max(block_number). 앵커·토큰 커서와 독립이다(컨트랙트별 쿼리). */
    @Transactional(readOnly = true)
    public Optional<Long> cursor(String contractAddress) {
        return events.findMaxBlockNumber(contractAddress);
    }

    @Transactional(readOnly = true)
    public Optional<LastEvent> lastEvent(String contractAddress) {
        return events.findTopByContractAddressOrderByBlockNumberDescLogIndexDesc(contractAddress)
                .map(e -> new LastEvent(e.getBlockNumber(), json.readValue(e.getPayload(), Payload.class).blockHash()));
    }

    /**
     * 이벤트가 안 온 PENDING 후보 — <b>풀 작업만</b>. 규칙·문턱은 토큰 인덱서와 같다({@code TokenIndexService} 의 상수 셋을 그대로 읽는다.
     * 값을 따로 두면 두 인덱서의 판정 시각이 어긋난다): tx_hash 없이 10분 → 바로 닫는다, tx_hash 있고 30초 → receipt 를 봐야 안다.
     */
    @Transactional(readOnly = true)
    public List<StalePending> findStalePending(Instant now) {
        List<StalePending> out = new ArrayList<>();
        for (Operation op :
                operations.findByKindInAndStatusAndTxHashIsNullAndCreatedAtBefore(
                        POOL_KINDS, Operation.Status.PENDING, now.minus(TokenIndexService.SEND_LOST_AFTER))) {
            out.add(new StalePending(op.getId(), null, op.getCreatedAt()));
        }
        for (Operation op :
                operations.findByKindInAndStatusAndTxHashIsNotNullAndCreatedAtBefore(
                        POOL_KINDS, Operation.Status.PENDING, now.minus(TokenIndexService.REVERT_CHECK_AFTER))) {
            out.add(new StalePending(op.getId(), op.getTxHash(), op.getCreatedAt()));
        }
        return out;
    }

    /**
     * 작업을 FAILED 로 닫고 배팅(EVENT_BET 이면)도 FAILED 로. 이미 PENDING 이 아니면 아무것도 하지 않는다 — 같은 회차에 이벤트가
     * 먼저 왔거나 토큰 인덱서가 먼저 닫았을 수 있다. 후자면 배팅은 {@link #reconcileFailedBets} 가 맞춘다.
     */
    @Transactional
    public void failOperation(String operationId, String code, String message) {
        Optional<Operation> found = operations.findById(operationId);
        if (found.isEmpty() || found.get().getStatus() != Operation.Status.PENDING) {
            return;
        }
        Operation op = found.get();
        op.markFailed(code, message);
        bets.findByOperationId(op.getId()).ifPresent(PredictionEventBet::fail);
        log.warn("작업 {} — {} ({}), kind {}", op.getId(), code, message, op.getKind());
    }

    /**
     * 작업은 FAILED 인데 배팅은 PENDING 인 행을 FAILED 로. 풀 인덱서가 아닌 쪽이 닫은 작업을 따라간다 — 토큰 인덱서 ⑤ 가 먼저 봤거나,
     * 전송 주체(REST)가 잔액 부족·revert 로 바로 닫았거나. REST 재시도 크론이 FAILED 를 새 작업으로 다시 보내면
     * {@code PredictionEventBet.retry} 가 작업 id 를 바꾸므로 여기 다시 걸리지 않는다.
     *
     * @return 이번에 FAILED 로 바꾼 수
     */
    @Transactional
    public int reconcileFailedBets() {
        int n = 0;
        for (PredictionEventBet b : bets.findPendingWithFailedOperation()) {
            b.fail();
            n++;
            log.warn("배팅(prediction {}) — 작업 {} 이 FAILED 라 배팅도 FAILED. 재전송은 REST 재시도 크론", b.getPredictionId(), b.getOperationId());
        }
        return n;
    }

    // ── 반영 ────────────────────────────────────────────────────────────

    /** 이벤트·종목·배팅 → 원장 → 작업. 이벤트 행은 이미 저장돼 있다. */
    private void apply(ChainEvent event, Payload p) {
        PoolLog.Kind kind = PoolLog.Kind.valueOf(p.kind());
        long eventId = Long.parseLong(p.eventId());
        String tx = event.getTxHash();
        switch (kind) {
            case EVENT_OPENED -> withEvent(eventId, event, e -> e.markOpened(tx));
            case BET_PLACED -> {
                BigInteger stake = new BigInteger(p.amount());
                Optional<User> user = users.findByWalletAddress(p.user());
                Optional<PredictionEventBet> bet =
                        user.flatMap(u -> bets.findByEventIdAndUserIdAndStockIdx(eventId, u.getId(), p.stockIdx().shortValue()));
                if (bet.isPresent()) {
                    PredictionEventBet b = bet.get();
                    if (b.getBetStatus() == PredictionEventBet.Status.FAILED) {
                        log.warn("배팅(prediction {}) — FAILED 였는데 BetPlaced 가 왔다(SEND_LOST 창에서 실제로 나간 tx). 체인이 진실이라 ACTIVE 로. tx {}", b.getPredictionId(), tx);
                    }
                    b.markPlaced(Long.parseLong(p.betId()), tx);
                } else {
                    log.error(
                            "BetPlaced tx {} — 짝 되는 배팅 행 없음(event {} · wallet {} · stockIdx {}). 서버가 접수한 배팅이 아니거나 DB 가 유실됐다. "
                                    + "원장은 이벤트대로 쓴다 — 사람이 확인해라",
                            tx, eventId, p.user(), p.stockIdx());
                }
                credit(user, p.user(), stake.negate(), TokenReason.EVENT_STAKE, event);
            }
            case EVENT_SEALED -> withEvent(eventId, event, e -> e.markSealed(tx));
            case BET_REVEALED -> withBet(eventId, Long.parseLong(p.betId()), event, b -> b.markRevealed(tx));
            case EVENT_REVEALED -> withEvent(eventId, event, PredictionEvent::markRevealed);
            case STOCK_RESOLVED -> {
                Optional<PredictionEventStock> stock = stocks.findByEventIdAndIdx(eventId, p.stockIdx().shortValue());
                if (stock.isEmpty()) {
                    log.error("StockResolved tx {} — 짝 되는 종목 칸 없음(event {} · idx {})", tx, eventId, p.stockIdx());
                } else {
                    PredictionEventStock s = stock.get();
                    BigDecimal base = fromChainPrice(p.basePrice());
                    if (s.getBasePrice() != null && s.getBasePrice().compareTo(base) != 0) {
                        // 컨트랙트가 판정에 쓴 기준가가 DB 와 다르다 — 러너가 넣은 값이 진실이고 DB 는 그대로 둔다. 사람이 본다.
                        log.warn("StockResolved event {} idx {} — 기준가가 다르다. DB {} / 체인 {}. tx {}", eventId, p.stockIdx(), s.getBasePrice(), base, tx);
                    }
                    s.markResolved(fromChainPrice(p.closePrice()), PredictionEventStock.Winner.ofChainValue(p.winner()), tx);
                }
            }
            case EVENT_RESOLVED -> withEvent(eventId, event, PredictionEvent::markResolved);
            case BET_SETTLED -> {
                BigInteger payout = new BigInteger(p.amount());
                withBet(eventId, Long.parseLong(p.betId()), event, b -> b.settle(payout, PredictionEventBet.Status.SETTLED, tx));
                if (payout.signum() > 0) {
                    credit(users.findByWalletAddress(p.user()), p.user(), payout, TokenReason.EVENT_PAYOUT, event);
                }
            }
            case BET_REFUNDED -> {
                BigInteger stake = new BigInteger(p.amount());
                withBet(eventId, Long.parseLong(p.betId()), event, b -> b.settle(stake, PredictionEventBet.Status.REFUNDED, tx));
                credit(users.findByWalletAddress(p.user()), p.user(), stake, TokenReason.EVENT_REFUND, event);
            }
            case EVENT_SETTLED -> withEvent(eventId, event, PredictionEvent::markSettled);
        }
        settleOperation(event, kind);
    }

    private void withEvent(long eventId, ChainEvent event, java.util.function.Consumer<PredictionEvent> action) {
        Optional<PredictionEvent> found = predictionEvents.findById(eventId);
        if (found.isEmpty()) {
            log.error("{} tx {} — 짝 되는 prediction_events 행 없음(event {}). 리허설·수동 tx 면 정상", event.getEventName(), event.getTxHash(), eventId);
            return;
        }
        action.accept(found.get());
    }

    private void withBet(long eventId, long betId, ChainEvent event, java.util.function.Consumer<PredictionEventBet> action) {
        Optional<PredictionEventBet> found = bets.findByEventIdAndBetIndex(eventId, betId);
        if (found.isEmpty()) {
            log.error("{} tx {} — 짝 되는 배팅 행 없음(event {} · betId {}). BetPlaced 가 먼저 반영됐는지 확인해라", event.getEventName(), event.getTxHash(), eventId, betId);
            return;
        }
        action.accept(found.get());
    }

    /** 회원 지갑이면 원장 한 행. 아니면 WARN 한 줄 — 리허설 계정·수동 tx 가 여기 걸린다. 이벤트는 이미 남아 있다. */
    private void credit(Optional<User> user, String wallet, BigInteger delta, TokenReason reason, ChainEvent event) {
        if (user.isEmpty()) {
            log.warn("{} {} ANT ({}) — 회원이 아닌 지갑 {}. 원장에 쓰지 않음. tx {}", event.getEventName(), delta, reason, wallet, event.getTxHash());
            return;
        }
        ledger.save(TokenLedger.record(user.get(), delta, reason.name(), event));
    }

    /**
     * 같은 tx 의 작업을 SUCCEEDED 로. PENDING 일 때만 — 이미 끝난 작업은 재훑기·수동 재처리의 정상 경로라 INFO 만.
     * 작업이 없으면: BetPlaced 는 돈이 움직였는데 접수 기록이 없는 것이라 ERROR(SEND_LOST 창). 나머지는 정산 러너의 tx 라
     * 작업 행이 원래 없다 — 조용히 지나간다.
     */
    private void settleOperation(ChainEvent event, PoolLog.Kind kind) {
        Optional<Operation> found = operations.findByTxHash(event.getTxHash());
        if (found.isEmpty()) {
            if (kind == PoolLog.Kind.BET_PLACED) {
                log.error(
                        "BetPlaced tx {} — 짝 되는 작업 없음. 서버가 보낸 tx 라면 SEND_LOST 창(전송~markSent 사이 크래시)이다: 토큰은 움직였고 작업은 FAILED 다. "
                                + "자동 환불하지 않는다 — 사람이 확인해라",
                        event.getTxHash());
            }
            return;
        }
        Operation op = found.get();
        if (op.getStatus() != Operation.Status.PENDING) {
            log.info("작업 {} — 이미 {} 다. 이벤트 {} 는 저장만", op.getId(), op.getStatus(), event.getTxHash());
            return;
        }
        op.markSucceeded(event.getTxHash());
        log.info("작업 {} — SUCCEEDED ({}, tx {}, {} {})", op.getId(), op.getKind(), event.getTxHash(), op.getResourceType(), op.getResourceId());
    }

    /** 컨트랙트 가격(×100 정수) → {@code numeric(14,2)}. */
    static BigDecimal fromChainPrice(String chainPrice) {
        return new BigDecimal(new BigInteger(chainPrice)).movePointLeft(2);
    }

    private static String str(BigInteger v) {
        return v == null ? null : v.toString();
    }
}
