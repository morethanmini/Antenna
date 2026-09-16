package ssafy.a507.backend.domain.chain.indexer;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.domain.chain.config.ChainProperties;
import ssafy.a507.backend.domain.chain.config.EventPoolProperties;
import ssafy.a507.backend.domain.chain.entity.Operation;

/**
 * 풀 인덱서 ③ (ANT-EVENT-02). 스케줄러가 5초마다 {@link #poll} 을 부른다.
 *
 * <pre>
 * ① 재처리   processed_at IS NULL 인 풀 이벤트 → 다시 반영              (운영자가 되돌린 행. 평소엔 0건)
 * ② 커서     메모리 커서 + 1, 없으면 max(DB 풀 커서, from-block)          (앵커·토큰 커서와 독립 — 컨트랙트별)
 * ③ reorg    풀 컨트랙트 마지막 이벤트 블록의 해시 ≠ 체인 → 알람 + 정지
 * ④ 구간     [from, head] 를 max-block-range 로 잘라 poolLogs → record (멱등)
 * ⑤ 정리     이벤트가 안 온 PENDING <b>풀 작업</b>(EVENT_BET · EVENT_ADMIN) — tx_hash 없음 10분 → SEND_LOST · receipt 0 → REVERTED ·
 *            receipt 없음 10분 → TX_DROPPED. 배팅도 같이 FAILED. 그리고 남이 먼저 닫은 작업의 배팅을 따라 적는다
 * </pre>
 *
 * <p>①~⑤ 전부 {@link TokenIndexer} 의 뼈대를 그대로 복제했다(plan §⑦ — 셋째 인덱서지만 공통화는 후속 이슈로 미뤘다. 사흘 안에
 * 기존 두 인덱서를 건드려 회귀를 만들지 않으려는 것). <b>reorg 검사를 한쪽만 고치면 어긋난다.</b>
 *
 * <p>⑤ 를 복제한 이유(09-16 결정): 토큰 인덱서 ⑤ 도 kind 를 안 가려 풀 작업을 닫을 수는 있지만, 그러면 이벤트 흐름이 토큰 인덱서가
 * 켜져 있느냐에 기댄다. 풀은 자기 작업만 보고 스스로 닫는다. 둘이 같은 작업을 봐도 "PENDING 일 때만" 이라 결과는 하나고,
 * 겹치는 비용은 receipt 조회 한 번뿐이다.
 *
 * <p>운영 {@code from-block} 은 토큰 배포 블록(11241835)이라 풀 배포 블록까지 10k 블록씩 몇 회차 훑고 끝난다 — 별도 env 는 두지 않는다.
 * confirmations = 0 · 단일 인스턴스 · 정지는 컴포넌트 플래그(재시작으로 풀림) — 전부 앵커·토큰 인덱서와 같다.
 */
@Slf4j
@Component
public class PoolIndexer {

    private final ChainLogSource source;
    private final PoolIndexService service;
    private final ChainProperties props;
    private final EventPoolProperties pool;

    private volatile boolean halted;
    private Long scannedUpTo;

    public PoolIndexer(ChainLogSource source, PoolIndexService service, ChainProperties props, EventPoolProperties pool) {
        this.source = source;
        this.service = service;
        this.props = props;
        this.pool = pool;
        if (isEnabled()) {
            log.info("풀 인덱서 준비: PredictionEventPool {}, 시작 블록 {}", pool.address(), props.indexer().fromBlock());
        } else {
            log.info("풀 인덱서 꺼짐 — CHAIN_RPC_URL · CONTRACT_EVENT_POOL 를 확인하라 (릴레이어 키는 필요 없다)");
        }
    }

    /** RPC 와 풀 주소만 있으면 된다. 읽기 전용이라 릴레이어 키는 안 본다. */
    public boolean isEnabled() {
        return props.hasRpcUrl() && pool.isDeployed();
    }

    public boolean isHalted() {
        return halted;
    }

    public Optional<Long> scannedUpTo() {
        return Optional.ofNullable(scannedUpTo);
    }

    /** 테스트 전용 — 재시작 직후 상태로. */
    synchronized void resetForTest() {
        halted = false;
        scannedUpTo = null;
    }

    public void poll() {
        poll(Instant.now());
    }

    /** 한 회차. {@code now} 는 {@code chain_events.processed_at} 에 찍힌다 — 테스트가 시각을 고정할 수 있게 받는다. */
    public synchronized void poll(Instant now) {
        if (!isEnabled() || halted) {
            return;
        }
        String address = pool.normalizedAddress();

        // ① 재처리
        for (Long id : service.findUnprocessedIds()) {
            try {
                service.reprocess(id, now);
            } catch (RuntimeException e) {
                log.error("chain_events #{} (풀) 재처리 실패", id, e);
            }
        }

        try {
            // ② 커서
            long from;
            if (scannedUpTo != null) {
                from = scannedUpTo + 1;
            } else {
                long dbCursor = service.cursor(address).map(b -> b + 1).orElse(0L);
                from = Math.max(dbCursor, props.indexer().fromBlock());
            }

            // ③ reorg 검사
            Optional<PoolIndexService.LastEvent> last = service.lastEvent(address);
            if (last.isPresent()) {
                Optional<String> onchain = source.blockHash(last.get().blockNumber());
                if (onchain.isEmpty() || !onchain.get().equalsIgnoreCase(last.get().blockHash())) {
                    halted = true;
                    log.error(
                            "풀 인덱서 정지 — 블록 {} 의 해시가 다르다. 기록 {} / 체인 {}. 이 체인(BFT)에서는 나면 안 되는 일이다. "
                                    + "chain_events 이후 행과 prediction_event_bets·token_ledger·operations 를 사람이 확인하고 서버를 재시작해라",
                            last.get().blockNumber(),
                            last.get().blockHash(),
                            onchain.orElse("(블록 없음)"));
                    return;
                }
            }

            // ④ 구간 루프
            long head = source.latestBlock();
            int range = Math.max(1, props.indexer().maxBlockRange());
            while (from <= head) {
                long to = Math.min(head, from + range - 1);
                List<PoolLog> logs = source.poolLogs(from, to);
                int inserted = 0;
                for (PoolLog l : logs) {
                    if (service.record(l, now)) {
                        inserted++;
                    }
                }
                if (!logs.isEmpty()) {
                    log.info("풀 인덱서 [{}, {}] — 로그 {}건, 신규 {}건", from, to, logs.size(), inserted);
                }
                scannedUpTo = to;
                from = to + 1;
            }

            // ⑤ 정리 — ④ 뒤인 이유: 같은 회차에 이벤트가 오면 SUCCEEDED 가 먼저 찍혀 정리 대상에서 빠진다.
            settleStale(now);
            service.reconcileFailedBets();
        } catch (BusinessException e) {
            log.warn("풀 인덱서 회차 건너뜀 — {}", e.getErrorCode());
        }
    }

    /**
     * 이벤트가 안 온 PENDING 풀 작업을 닫는다. 규칙은 {@link TokenIndexer#settleStale} 와 같다. receipt 조회는 트랜잭션 밖(여기)에서 하고
     * 판정 결과만 서비스가 기록한다. receipt 가 있고 성공인데 이벤트가 아직 없으면 그대로 둔다 — 인덱서 지연이고 다음 회차가 잡는다.
     */
    private void settleStale(Instant now) {
        for (PoolIndexService.StalePending s : service.findStalePending(now)) {
            if (s.txHash() == null) {
                service.failOperation(
                        s.operationId(), Operation.ChainFailure.SEND_LOST.name(), "전송 주체가 tx 를 남기지 못했다(전송 실패 또는 크래시)");
                continue;
            }
            Optional<Boolean> receipt = source.receiptStatus(s.txHash());
            if (receipt.isPresent() && !receipt.get()) {
                service.failOperation(
                        s.operationId(), Operation.ChainFailure.REVERTED.name(), "채굴에서 revert — 시뮬레이션 뒤 상태가 바뀌었다(잔액·봉인). tx " + s.txHash());
            } else if (receipt.isEmpty() && s.createdAt().plus(TokenIndexService.DROPPED_AFTER).isBefore(now)) {
                service.failOperation(
                        s.operationId(), Operation.ChainFailure.TX_DROPPED.name(), "receipt 가 10분 안에 나오지 않았다. tx " + s.txHash());
            }
        }
    }
}
