package ssafy.a507.backend.domain.event.service;

import java.math.BigInteger;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.utils.Numeric;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.chain.relay.PoolRelayer;
import ssafy.a507.backend.domain.chain.relay.PoolRevertException;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;

/**
 * 풀 컨트랙트 tx 를 <b>트랜잭션 밖에서</b> 보낸다 (ANT-EVENT-05, 작업명세 v1.2 §5).
 *
 * <p>{@code TokenOperationService} 와 같은 호출자 계약이다 — 접수(예측·배팅·작업 PENDING)를 먼저
 * 커밋하고 그 뒤에 보낸다. 거꾸로 하면 <b>돈은 풀로 갔는데 배팅 행이 없는</b> 상태가 생기고, 인덱서는
 * 로그의 (이벤트, 지갑, 종목 idx)로 행을 찾으므로 그 배팅은 영영 주인을 못 찾는다(§0.3). 실수로
 * {@code @Transactional} 을 붙이면 여기서 바로 터뜨린다.
 *
 * <p><b>던지지 않는다.</b> 묶음 등록의 응답은 늘 202 다 — 열 종목 중 아홉이 나가고 하나가 실패했다고
 * 요청 전체를 실패로 돌릴 수 없고, 그 하나는 재시도 크론이 봉인 전까지 다시 보낸다. 실패를 세 갈래로
 * 가른다.
 *
 * <ul>
 *   <li>{@code CHAIN_UNAVAILABLE} — 작업을 <b>PENDING 으로 둔다.</b> RPC 가 돌아오면 같은 돈이
 *       나갈 수 있고, 안 돌아오면 10분 뒤 인덱서 ②가 {@code SEND_LOST} 로 닫는다. 여기서 FAILED 로
 *       닫으면 "실은 나갔던 tx" 를 못 받는다
 *   <li>{@code INSUFFICIENT_BALANCE} — 작업 FAILED. 다시 보내도 같다. 충전하면 재시도 크론이 집는다
 *   <li>{@link PoolRevertException} · 그 밖 — 작업 FAILED(INTERNAL_ERROR). 호출자 검증을 뚫은 서버 버그다
 * </ul>
 *
 * <p>봉인 tx 도 여기로 보낸다. 배팅과 다른 건 인자뿐이고, 작업을 {@code markSent}/{@code markFailed}
 * 로 닫는 규칙이 같다 — 클래스를 하나 더 두면 그 규칙이 두 벌이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventBetDispatcher {

    private final PoolRelayer pool;
    private final OperationRepository operations;
    /** markSent · markFailed 는 각각 짧은 트랜잭션이다 — 전송 자체는 어떤 트랜잭션에도 들어가면 안 된다. */
    private final TransactionTemplate tx;

    /**
     * 배팅 한 건의 전송 재료. 접수 트랜잭션 안에서 다 뽑아 온다 — 밖에서 엔티티를 만지면
     * {@code open-in-view: false} 때문에 지연 로딩이 터진다.
     */
    public record Bet(
            String operationId,
            long eventId,
            String wallet,
            int stockIdx,
            BigInteger stake,
            String commitHex) {}

    /** 묶음 등록·재시도 둘 다 여기로 온다. 한 건이 실패해도 나머지는 계속 보낸다. */
    public void dispatchBets(List<Bet> bets) {
        for (Bet bet : bets) {
            send(
                    bet.operationId(),
                    "placeBet",
                    () ->
                            pool.placeBet(
                                    bet.eventId(),
                                    bet.wallet(),
                                    bet.stockIdx(),
                                    bet.stake(),
                                    Numeric.hexStringToByteArray(bet.commitHex())));
        }
    }

    /** 봉인. SEALED 전이는 인덱서가 한다 — 여기서는 tx 만 보낸다(§0.3). */
    public void dispatchSeal(String operationId, long eventId) {
        send(operationId, "seal", () -> pool.seal(eventId));
    }

    private void send(String operationId, String what, java.util.function.Supplier<String> call) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "EventBetDispatcher 는 트랜잭션 밖에서 불러야 한다 — 접수를 먼저 커밋하고 tx 를 보낸다");
        }

        String txHash;
        try {
            txHash = call.get();
        } catch (BusinessException e) {
            if (e.getErrorCode() == ErrorCode.CHAIN_UNAVAILABLE) {
                // PENDING 그대로 둔다. 인덱서 ②가 10분 뒤 SEND_LOST 로 닫고, 재시도 크론이 다시 보낸다.
                log.warn("작업 {} — 풀 {} 전송 불가(체인). PENDING 유지", operationId, what);
                return;
            }
            fail(operationId, e.getErrorCode().name(), e.getMessage());
            return;
        } catch (PoolRevertException e) {
            // 호출자 검증을 뚫고 온 컨트랙트 거부(BadState · AlreadyBet · BadCommit). 다시 보내도 같다.
            fail(operationId, ErrorCode.INTERNAL_ERROR.name(), e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail(
                    operationId,
                    ErrorCode.INTERNAL_ERROR.name(),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            return;
        }

        tx.execute(
                s -> {
                    operations.findById(operationId).orElseThrow().markSent(txHash);
                    return null;
                });
        log.info("작업 {} — 풀 {} tx {}", operationId, what, txHash);
    }

    /** {@code code} 는 닫힌 어휘({@code ErrorCode} 이름)만. 원문은 300자 컬럼에 맞춰 자른다. */
    private void fail(String operationId, String code, String message) {
        String trimmed = message == null ? "" : message.length() > 300 ? message.substring(0, 300) : message;
        tx.execute(
                s -> {
                    operations.findById(operationId).ifPresent(op -> op.markFailed(code, trimmed));
                    return null;
                });
        log.warn("작업 {} — 풀 tx 전송 실패 {}: {}", operationId, code, trimmed);
    }
}
