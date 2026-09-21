package ssafy.a507.backend.domain.chain.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.config.EventPoolProperties;
import ssafy.a507.backend.domain.chain.indexer.ChainLogSource;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.chain.relay.PoolRevertException;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 이벤트 정산 러너 (ANT-EVENT-03). 관리자가 정산 버튼을 누르면 REST 가 부른다 — 크론은 없다.
 *
 * <pre>
 * 0 잠금     같은 이벤트가 이미 도는 중이면 EVENT_SETTLING. 메모리 잠금(단일 인스턴스)
 * 1 전제     DB 봉인됨 · 종목마다 기준가·종가 · 체인에 올라간 배팅의 예측이 전부 HIT/MISS. 아니면 EVENT_NOT_READY
 * 2 리빌     체인이 SEALED 면: ACTIVE 배팅 중 체인이 "아직 안 밝힘" 인 것만 100건씩 reveal → revealDone
 * 3 판정     체인이 REVEALED 면: 종목마다 체인이 "아직 안 판정" 인 것만 resolveStock → resolveDone
 * 4 정산     체인이 RESOLVED 면: ACTIVE·REVEALED 배팅 중 체인이 "아직 안 정산" 인 것만 100건씩 settle → sweep
 * 5 검산     체인이 SETTLED · settledCount == betCount · escrow == 0
 * </pre>
 *
 * <p><b>멱등의 근거는 온체인이다.</b> 단계는 {@code eventInfo.status} 로 건너뛰고, 배팅은 DB 로 후보를 좁힌 뒤 {@code bet()} 으로 확인한다.
 * DB 만 믿으면 인덱서 5초 지연 사이에 방금 밝힌 배팅을 또 넣어 묶음 전체가 {@code AlreadyRevealed} 로 revert 한다.
 * 후보마다 {@code eth_call} 한 번(400건이면 수 초)이 그 대가다.
 *
 * <p>명령마다 receipt 를 기다린다(1초 폴링, 상한은 설정). 앞 명령이 채굴되기 전에 다음을 보내면 컨트랙트가 {@code BadState} 로 거부한다.
 * RPC 장애는 그 명령만 재시도하고, revert 는 즉시 멈춘다 — 다시 보내도 같다. 멈추면 실행 기록이 FAILED 로 남고, 다시 누르면
 * 온체인 상태로 이어 간다. DB 의 배팅·이벤트 상태는 러너가 쓰지 않는다 — 인덱서 ③ 이 로그로 따라 적는다.
 *
 * <p>순위 굳힘은 여기 없다. REST 의 정산 엔드포인트가 {@code run(id)} 뒤에 {@code EventResultService.freeze(id)} 를 잇는다(09-16 결정).
 */
@Slf4j
@Component
public class EventSettlementRunner {

    /**
     * receipt 폴링 간격.
     *
     * <p>1초였다. "블록이 2~10초라 더 촘촘할 이유가 없다" 는 전제였는데, 그 전제는 <b>tx 하나</b>를 볼 때만 맞다.
     * 정산은 reveal · resolve ×10 · settle · sweep 을 순서대로 보내고 명령마다 receipt 를 기다리므로, 한 번에
     * 평균 0.5초씩 늦는 것이 tx 수만큼 곱해진다 — 배팅 300건이면 tx 가 20개를 넘어 10초 이상이 폴링 간격에서만 샌다
     * (QA 09-21 "돈이 제일 늦게 들어온다").
     *
     * <p>250ms 로 줄인다. 늘어나는 것은 {@code eth_getTransactionReceipt} 호출 수뿐이고, 그건 상태를 바꾸지 않는
     * 가장 가벼운 RPC 다. 블록이 2초여도 tx 하나당 조회가 8번을 넘지 않는다.
     */
    static final long RECEIPT_POLL_MILLIS = 250L;

    private final PoolRelayer relayer;
    private final ChainLogSource chain;
    private final EventSettlementService service;
    private final EventPoolProperties.Settlement settings;
    /** 이벤트 id → 실행 중. 단일 인스턴스 가정은 앵커·인덱서와 같다. */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public EventSettlementRunner(PoolRelayer relayer, ChainLogSource chain, EventSettlementService service, EventPoolProperties pool) {
        this.relayer = relayer;
        this.chain = chain;
        this.service = service;
        this.settings = pool.settlementOrDefault();
    }

    /** 한 번의 실행 결과. {@code alreadySettled} 면 체인이 이미 SETTLED 라 아무 tx 도 보내지 않았다. */
    public record RunReport(long runId, boolean alreadySettled, int revealed, int resolved, int settled) {}

    /** REST 상태 API 용 — 이벤트의 마지막 실행 기록. */
    public Optional<EventSettlementRun> state(long eventId) {
        return service.latestRun(eventId);
    }

    /**
     * 정산 준비 상태 — DB 전제({@code EventSettlementService.preflight}) + 체인 상태. REST 상태 API 가 "무엇이 빠졌나" 를 보여주는 재료.
     * {@code BusinessException} 은 코드만 실어 나르고 문구를 못 바꾸므로({@code common/error}), 빠진 항목은 예외가 아니라 여기서 읽는다.
     */
    public EventSettlementService.Preflight readiness(long eventId) {
        EventSettlementService.Preflight pre = service.preflight(eventId);
        if (pre.dbStatus() == PredictionEvent.Status.SETTLED || !relayer.isEnabled()) {
            return pre;
        }
        PoolRelayer.Status onChain = relayer.eventInfo(eventId).status();
        if (onChain == PoolRelayer.Status.NONE || onChain == PoolRelayer.Status.OPEN) {
            // DB 는 봉인됐는데 체인은 아니다 — 봉인 tx 가 아직 안 채굴됐거나 인덱서보다 DB 가 앞섰다. 러너가 봉인을 대신하지 않는다.
            List<String> reasons = new ArrayList<>(pre.reasons());
            reasons.add("체인 상태가 " + onChain + " — 봉인(seal)이 먼저다");
            return new EventSettlementService.Preflight(pre.eventId(), pre.dbStatus(), pre.stockCount(), pre.stockPrices(), reasons);
        }
        return pre;
    }

    /**
     * 정산 실행. 끝까지 돌거나(DONE) 예외로 멈춘다(FAILED). 호출 스레드에서 몇 분이 걸릴 수 있다 — 비동기로 돌리고 202 를 내는 건
     * 부르는 쪽(REST) 몫이다.
     *
     * @throws BusinessException EVENT_SETTLING · EVENT_NOT_READY · CHAIN_UNAVAILABLE · EVENT_SETTLE_TX_REVERTED · EVENT_SETTLE_TIMEOUT
     */
    public RunReport run(long eventId) {
        if (!relayer.isEnabled()) {
            throw new BusinessException(ErrorCode.CHAIN_UNAVAILABLE);
        }
        if (!running.add(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_SETTLING);
        }
        try {
            return runLocked(eventId);
        } finally {
            running.remove(eventId);
        }
    }

    private RunReport runLocked(long eventId) {
        // 1 전제 — 실행 행을 만들기 전에 본다. 조건이 안 갖춰진 클릭은 기록할 가치가 없다.
        EventSettlementService.Preflight pre = readiness(eventId);
        if (pre.dbStatus() == PredictionEvent.Status.SETTLED) {
            log.info("이벤트 {} — DB 가 이미 SETTLED. 아무것도 보내지 않는다", eventId);
            long runId = service.startRun(eventId);
            service.finish(runId);
            return new RunReport(runId, true, 0, 0, 0);
        }
        if (!pre.ready()) {
            log.warn("이벤트 {} 정산 불가 — {}", eventId, String.join(" · ", pre.reasons()));
            throw new BusinessException(ErrorCode.EVENT_NOT_READY);
        }

        long runId = service.startRun(eventId);
        int revealed = 0;
        int resolved = 0;
        int settled = 0;
        try {
            PoolRelayer.EventInfo info = relayer.eventInfo(eventId);

            // 2 리빌
            if (info.status() == PoolRelayer.Status.SEALED) {
                revealed = reveal(eventId, runId);
                send("revealDone", () -> relayer.revealDone(eventId));
                info = relayer.eventInfo(eventId);
            }

            // 3 판정
            if (info.status() == PoolRelayer.Status.REVEALED) {
                resolved = resolve(eventId, runId, pre);
                send("resolveDone", () -> relayer.resolveDone(eventId));
                info = relayer.eventInfo(eventId);
            }

            // 4 정산
            if (info.status() == PoolRelayer.Status.RESOLVED) {
                settled = settle(eventId, runId);
                service.progress(runId, EventSettlementRun.Phase.SWEEPING, 0, 1);
                send("sweep", () -> relayer.sweep(eventId));
                info = relayer.eventInfo(eventId);
            }

            // 5 검산
            service.progress(runId, EventSettlementRun.Phase.VERIFYING, 0, 1);
            verify(eventId, info);
            service.finish(runId);
            log.info("이벤트 {} 정산 완료 — 실행 #{} · 리빌 {} · 판정 {} · 정산 {} · 배팅 {}건", eventId, runId, revealed, resolved, settled, info.betCount());
            return new RunReport(runId, false, revealed, resolved, settled);
        } catch (SettlementFailure e) {
            service.fail(runId, e.getErrorCode().name(), e.detail);
            throw e;
        } catch (BusinessException e) {
            service.fail(runId, e.getErrorCode().name(), e.getMessage());
            throw e;
        } catch (PoolRevertException e) {
            service.fail(runId, "POOL_REVERT", e.getErrorName());
            throw new SettlementFailure(ErrorCode.EVENT_SETTLE_TX_REVERTED, "컨트랙트 거부 " + e.getErrorName());
        } catch (RuntimeException e) {
            service.fail(runId, ErrorCode.INTERNAL_ERROR.name(), e.toString());
            log.error("이벤트 {} 정산 실행 #{} 예기치 않은 실패", eventId, runId, e);
            throw e;
        }
    }

    // ── 단계 ─────────────────────────────────────────────────────────────

    private int reveal(long eventId, long runId) {
        List<EventSettlementService.RevealItem> items = service.revealItems(eventId);
        List<Long> betIds = new ArrayList<>();
        List<Prediction.Direction> sides = new ArrayList<>();
        List<String> hashes = new ArrayList<>();
        for (EventSettlementService.RevealItem it : items) {
            if (it.noteHash() == null) {
                continue; // 재료 없음 — settle 이 원금을 돌려준다
            }
            if (relayer.bet(eventId, it.betIndex()).revealed()) {
                continue; // 인덱서가 아직 못 따라온 것. 넣으면 묶음 전체가 AlreadyRevealed
            }
            betIds.add(it.betIndex());
            sides.add(it.side());
            hashes.add(it.noteHash());
        }
        int total = betIds.size();
        service.progress(runId, EventSettlementRun.Phase.REVEALING, 0, total);
        int done = 0;
        for (int from = 0; from < total; from += settings.chunkSize()) {
            int to = Math.min(total, from + settings.chunkSize());
            List<Long> ids = betIds.subList(from, to);
            List<Prediction.Direction> ss = sides.subList(from, to);
            List<String> hs = hashes.subList(from, to);
            send("reveal", () -> relayer.reveal(eventId, ids, ss, hs));
            done = to;
            service.progress(runId, EventSettlementRun.Phase.REVEALING, done, total);
        }
        return done;
    }

    private int resolve(long eventId, long runId, EventSettlementService.Preflight pre) {
        int total = pre.stockPrices().size();
        service.progress(runId, EventSettlementRun.Phase.RESOLVING, 0, total);
        int done = 0;
        int sent = 0;
        for (EventSettlementService.StockPrice p : pre.stockPrices()) {
            if (!relayer.result(eventId, p.idx()).resolved()) {
                send("resolveStock " + p.idx(), () -> relayer.resolveStock(eventId, p.idx(), p.basePrice(), p.closePrice()));
                sent++;
            }
            done++;
            service.progress(runId, EventSettlementRun.Phase.RESOLVING, done, total);
        }
        return sent;
    }

    private int settle(long eventId, long runId) {
        List<Long> betIds = new ArrayList<>();
        for (Long betId : service.settleCandidates(eventId)) {
            if (!relayer.bet(eventId, betId).settled()) {
                betIds.add(betId);
            }
        }
        int total = betIds.size();
        service.progress(runId, EventSettlementRun.Phase.SETTLING, 0, total);
        int done = 0;
        for (int from = 0; from < total; from += settings.chunkSize()) {
            int to = Math.min(total, from + settings.chunkSize());
            List<Long> ids = betIds.subList(from, to);
            send("settle", () -> relayer.settle(eventId, ids));
            done = to;
            service.progress(runId, EventSettlementRun.Phase.SETTLING, done, total);
        }
        return done;
    }

    /** 지라 수용 기준 "정산 합계 = 풀 − 잔돈" 의 온체인 판: 전부 정산됐고(settledCount == betCount) 이벤트 몫 잔액이 0(잔돈까지 treasury 로). */
    private void verify(long eventId, PoolRelayer.EventInfo info) {
        if (info.status() != PoolRelayer.Status.SETTLED
                || info.settledCount() != info.betCount()
                || info.escrow().signum() != 0) {
            String msg =
                    "검산 실패 — status " + info.status() + " · settled " + info.settledCount() + "/" + info.betCount() + " · escrow " + info.escrow();
            log.error("이벤트 {} {}", eventId, msg);
            throw new SettlementFailure(ErrorCode.EVENT_SETTLE_TX_REVERTED, msg);
        }
    }

    // ── 전송 · 대기 ───────────────────────────────────────────────────────

    /**
     * 명령 하나: 전송 → receipt 대기. RPC 장애만 재시도. {@link PoolRevertException} 은 시뮬레이션에서 컨트랙트가 거부한 것이라
     * 위로 던진다(다시 보내도 같다).
     */
    private void send(String what, Supplier<String> tx) {
        String txHash = null;
        for (int attempt = 1; attempt <= settings.retryCount(); attempt++) {
            try {
                txHash = tx.get();
                break;
            } catch (BusinessException e) {
                if (e.getErrorCode() != ErrorCode.CHAIN_UNAVAILABLE || attempt == settings.retryCount()) {
                    throw e;
                }
                log.warn("정산 {} 전송 실패 {}/{} — RPC 장애, {}초 뒤 재시도", what, attempt, settings.retryCount(), settings.retryDelaySeconds());
                sleep(settings.retryDelaySeconds() * 1000L);
            }
        }
        log.info("정산 {} tx {} — receipt 대기", what, txHash);
        awaitReceipt(what, txHash);
    }

    private void awaitReceipt(String what, String txHash) {
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + settings.receiptTimeoutSeconds() * 1000L;
        while (true) {
            Optional<Boolean> status = chain.receiptStatus(txHash);
            if (status.isPresent()) {
                if (status.get()) {
                    /* 명령마다 채굴에 얼마나 걸렸는지 남긴다. 정산 전체가 늦다는 말은 들었는데
                       어느 명령이 끄는지는 로그에 없었다 — 이 줄이 그 답을 준다(QA 09-21). */
                    log.info("정산 {} tx {} — 채굴 {}ms", what, txHash, System.currentTimeMillis() - startedAt);
                    return;
                }
                log.error("정산 {} tx {} — 채굴에서 revert. 시뮬레이션 뒤 상태가 바뀌었다(다른 전송자?)", what, txHash);
                throw new SettlementFailure(ErrorCode.EVENT_SETTLE_TX_REVERTED, what + " tx " + txHash + " 채굴에서 revert");
            }
            if (System.currentTimeMillis() >= deadline) {
                log.error("정산 {} tx {} — {}초 안에 receipt 없음", what, txHash, settings.receiptTimeoutSeconds());
                throw new SettlementFailure(ErrorCode.EVENT_SETTLE_TIMEOUT, what + " tx " + txHash + " receipt 없음");
            }
            sleep(RECEIPT_POLL_MILLIS);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SettlementFailure(ErrorCode.EVENT_SETTLE_TIMEOUT, "스레드 인터럽트");
        }
    }

    /**
     * 러너 안에서 난 실패의 운영자용 원문(tx 해시 · 단계). {@code BusinessException} 은 문구를 못 바꾸므로 원문은 실행 행의
     * {@code error_message} 에만 남기고 응답에는 코드만 나간다(작업 테이블과 같은 규칙).
     */
    static final class SettlementFailure extends BusinessException {
        final String detail;

        SettlementFailure(ErrorCode code, String detail) {
            super(code);
            this.detail = detail;
        }
    }

    /** 테스트용 — 잠금 상태. */
    boolean isRunning(long eventId) {
        return running.contains(eventId);
    }
}
