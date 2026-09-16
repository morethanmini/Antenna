package ssafy.a507.backend.domain.event.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.common.security.WalletSigned;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.commit.EventCommits;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenRelayer;
import ssafy.a507.backend.domain.chain.service.OperationService;
import ssafy.a507.backend.domain.event.dto.EventEntryCreateRequest;
import ssafy.a507.backend.domain.event.dto.EventEntryCreateResponse;
import ssafy.a507.backend.domain.event.dto.EventHomeResponse.Me;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventStockRepository;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.commit.CommitHashes;
import ssafy.a507.backend.domain.prediction.commit.CommitPayload;
import ssafy.a507.backend.domain.prediction.commit.PredictionCommitFactory;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.entity.PredictionEvidence;
import ssafy.a507.backend.domain.prediction.entity.PredictionNote;
import ssafy.a507.backend.domain.prediction.repository.PredictionCommitRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionEvidenceRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionNoteRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;
import ssafy.a507.backend.domain.prediction.service.PredictionRegisterService;
import ssafy.a507.backend.domain.research.entity.ResearchPoint;

/**
 * 배팅 이벤트 묶음 등록 — ANT-EVENT-05, 화면 J-01. 작업명세 v1.2 §4 · §5.
 *
 * <p><b>왜 한 요청에 5~10 종목인가</b> — "첫 참가는 5종목 이상" 을 등록 시점에 막으려면 다섯 개가
 * 한 요청에 있어야 하고, 서명도 한 번이면 지갑 팝업이 다섯 번 뜨지 않는다. 종목마다 따로 받으면
 * 그 사이 하나만 거절해도 규칙이 깨진 채 절반만 등록된다.
 *
 * <p><b>트랜잭션을 손으로 나눈다</b>({@link EventBetDispatcher} 의 호출자 계약, §0.3): 검사·서명·저장을
 * 한 번에 커밋하고 {@code placeBet} 은 그 뒤에 보낸다. 거꾸로 하면 돈은 풀로 갔는데 배팅 행이 없어
 * 인덱서가 그 로그의 주인을 못 찾는다. 그래서 {@link #register} 에는 {@code @Transactional} 이 없다.
 *
 * <p><b>기존 등록({@code POST /predictions})은 건드리지 않는다.</b> 항목별 검사는
 * {@link PredictionRegisterService} 의 메서드를 그대로 부른다 — 규칙을 복사하면 두 경로가 조용히
 * 갈린다. 다른 것은 셋뿐이다: 슬롯을 세지 않고(이벤트 참가는 무료 3건과 무관), 날짜를 오늘이 아니라
 * 이벤트에서 가져오고, 배팅·작업 행이 함께 생긴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventEntryService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final EntityManager em;
    private final PredictionEventRepository events;
    private final PredictionEventStockRepository eventStocks;
    private final PredictionEventBetRepository bets;
    private final PredictionRepository predictions;
    private final PredictionNoteRepository notes;
    private final PredictionEvidenceRepository evidences;
    private final PredictionCommitRepository commits;
    private final OperationService operationService;
    private final PredictionCommitFactory commitFactory;
    private final PredictionRegisterService predictionRegisterService;
    private final SignatureGuard signatureGuard;
    private final TokenRelayer tokenRelayer;
    private final EventBetDispatcher dispatcher;
    private final TransactionTemplate tx;

    /** 접수 결과 — 응답과 전송 목록. 트랜잭션 밖으로 나가는 값이라 엔티티를 담지 않는다. */
    private record Accepted(EventEntryCreateResponse response, List<EventBetDispatcher.Bet> sends) {}

    /**
     * 묶음 등록. 응답은 <b>항상 202</b> 다 — 저장은 끝났고 체인 전송은 확인 대상이라, 화면은 종목마다
     * {@code operationId} 로 {@code GET /operations/{id}} 를 폴링한다.
     */
    public EventEntryCreateResponse register(
            Long userId, Long eventId, EventEntryCreateRequest request) {
        Accepted accepted = tx.execute(s -> accept(userId, eventId, request));
        dispatcher.dispatchBets(accepted.sends());
        return accepted.response();
    }

    /**
     * 접수. 검사 순서는 작업명세 §4 그대로고, <b>서명이 맨 뒤</b>인 것이 그중 핵심이다.
     *
     * <p>{@code SignatureGuard} 는 nonce 를 태운다. 서명을 먼저 검증하면 "이미 건 종목" 으로 409 를
     * 받는 요청에서도 nonce 가 사라져 사용자가 지갑에서 다섯 종목을 다시 서명해야 한다. 앞의 것들은
     * 상태를 바꾸지 않는 판정이라 앞에 둔다. 거꾸로 서명은 <b>행을 만들기 직전</b>이어야 한다 —
     * 검증 없이 만들면 남의 지갑에서 돈이 나가는 배팅을 만들 수 있다.
     */
    private Accepted accept(Long userId, Long eventId, EventEntryCreateRequest request) {
        // ① 사용자 행 잠금 — 이 사용자의 등록을 직렬화한다. 안 잡으면 동시 요청 둘이 "이미 건 종목"
        //    검사를 나란히 통과해 같은 종목에 두 번 건다(UNIQUE 가 막지만 그때는 500 이다).
        User user = em.find(User.class, userId, LockModeType.PESSIMISTIC_WRITE);
        if (user == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }

        // ② 접수 창.
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
        requireOpen(event);

        // ③ 항목 중복. 같은 종목 두 줄이면 ⑤ 의 "이미 건 종목" 검사를 둘 다 빠져나간다.
        List<EventEntryCreateRequest.Item> items = request.items();
        Set<String> codes = new HashSet<>();
        for (EventEntryCreateRequest.Item item : items) {
            if (!codes.add(item.stockCode())) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST, "items");
            }
        }

        // ④ 첫 참가는 min_stocks 이상. 두 번째부터는 한 종목씩 더해도 된다.
        List<PredictionEventBet> already = bets.findMine(eventId, userId);
        if (already.isEmpty() && items.size() < event.getMinStocks()) {
            throw new BusinessException(ErrorCode.EVENT_MIN_STOCKS, "items");
        }

        // ⑤ 이벤트 종목인가 · 이미 걸었나.
        Map<String, PredictionEventStock> byCode = new HashMap<>();
        for (PredictionEventStock card : eventStocks.findCardsByEventId(eventId)) {
            byCode.put(card.getStock().getCode(), card);
        }
        Set<Long> betStockIds = new HashSet<>();
        for (PredictionEventBet bet : already) {
            betStockIds.add(bet.getEventStock().getId());
        }
        List<PredictionEventStock> slots = new ArrayList<>(items.size());
        for (EventEntryCreateRequest.Item item : items) {
            PredictionEventStock slot = byCode.get(item.stockCode());
            if (slot == null) {
                throw new BusinessException(ErrorCode.EVENT_STOCK_NOT_INCLUDED, "stockCode");
            }
            if (!betStockIds.add(slot.getId())) {
                throw new BusinessException(ErrorCode.EVENT_STOCK_ALREADY_BET, "stockCode");
            }
            slots.add(slot);
        }

        // ⑥ 배팅 금액 범위 + 합계.
        BigInteger total = BigInteger.ZERO;
        for (EventEntryCreateRequest.Item item : items) {
            if (item.stake().compareTo(event.getMinStake()) < 0
                    || item.stake().compareTo(event.getMaxStake()) > 0) {
                throw new BusinessException(ErrorCode.EVENT_STAKE_OUT_OF_RANGE, "stake");
            }
            total = total.add(item.stake());
        }

        // ⑦ 잔액. 기준은 체인 잔액이다 — 원장은 인덱서 지연이 있어 방금 쓴 돈이 아직 남아 보인다.
        //    전송 중인 내 배팅(PENDING)도 빼야 한다. 안 빼면 잔액 1,000 에 500 짜리를 세 번 넣을 수 있다.
        String wallet = user.getWalletAddress();
        if (wallet == null || wallet.isBlank()) {
            throw new BusinessException(ErrorCode.WALLET_NOT_LINKED);
        }
        BigInteger needed = total.add(bets.sumPendingStake(userId));
        if (tokenRelayer.balanceOf(wallet).compareTo(needed) < 0) {
            throw new BusinessException(ErrorCode.EVENT_INSUFFICIENT_BALANCE);
        }

        // ⑧ 항목별 기존 검사 — 상장 · 방향 모순 · 근거 포인트 · noteSalt. 등록 서비스의 규칙 그대로다.
        LocalDate today = LocalDate.now(KST);
        List<Checked> checked = new ArrayList<>(items.size());
        for (EventEntryCreateRequest.Item item : items) {
            Stock stock = byCode.get(item.stockCode()).getStock();
            if (!stock.isListed()) {
                throw new BusinessException(ErrorCode.STOCK_NOT_FOUND, "stockCode");
            }
            BigDecimal refClose = predictionRegisterService.latestClose(stock, today);
            PredictionRegisterService.requireDirectionMatches(
                    item.direction(), item.targetPrice(), refClose);
            List<ResearchPoint> points =
                    predictionRegisterService.loadEvidences(item.evidencePointIdsOrEmpty(), stock);
            CommitHashes.requireNoteSalt(item.noteSalt());
            checked.add(new Checked(stock, refClose, points));
        }

        // ⑨ 서명 — 여기서 nonce 가 태워진다. 이 뒤로 실패하면 사용자는 다시 서명해야 한다.
        SignatureGuard.Verified verified =
                signatureGuard.verifyWithNonce(userId, Signed.of(eventId, items, request.signature()));

        // ⑩ 저장 — 예측 · 근거 · 커밋 · 배팅(PENDING) · 작업(PENDING) 을 한 트랜잭션에. 행이 먼저
        //    있어야 인덱서가 BetPlaced 의 주인을 찾는다(§0.3).
        short horizon = horizonOf(event);
        List<EventEntryCreateResponse.Entry> entries = new ArrayList<>(items.size());
        List<EventBetDispatcher.Bet> sends = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            EventEntryCreateRequest.Item item = items.get(i);
            PredictionEventStock slot = slots.get(i);
            Checked c = checked.get(i);

            Prediction prediction =
                    predictions.save(Prediction.registerForEvent(
                            user,
                            c.stock(),
                            item.direction(),
                            item.targetPrice(),
                            c.refClose(),
                            horizon,
                            event.getBaseDate(),
                            event.getSettleDate()));
            notes.save(PredictionNote.of(prediction, item.noteOrEmpty()));
            evidences.saveAll(
                    c.points().stream().map(p -> PredictionEvidence.of(prediction, p)).toList());
            // 앵커 커밋은 기존 v1 그대로다(§6) — 00:05 앵커 배치가 평소처럼 집어 간다. 묶음의 항목
            // 전부가 같은 서명 문자열을 공유한다: 서명이 한 번뿐이라 검산도 그 한 줄로 한다.
            commits.save(commitFactory.seal(
                    prediction,
                    item.noteOrEmpty(),
                    item.noteSalt(),
                    request.signature(),
                    verified.signerAddress(),
                    verified.nonce()));

            Operation operation =
                    operationService.accept(
                            user,
                            Operation.Kind.EVENT_BET,
                            Operation.ResourceType.PREDICTION,
                            prediction.getId());
            String commitHex =
                    EventCommits.commitHex(
                            eventId,
                            slot.getIdx(),
                            item.direction(),
                            CommitHashes.noteHash(item.noteOrEmpty(), item.noteSalt()));
            bets.save(PredictionEventBet.place(
                    prediction, event, slot, item.stake(), commitHex, operation.getId()));

            entries.add(new EventEntryCreateResponse.Entry(
                    item.stockCode(), prediction.getId(), commitHex, operation.getId()));
            sends.add(new EventBetDispatcher.Bet(
                    operation.getId(), eventId, wallet, slot.getIdx(), item.stake(), commitHex));
        }

        return new Accepted(
                new EventEntryCreateResponse(
                        eventId, entries, meOf(event, already, items, byCode.size())),
                sends);
    }

    /** ⑧ 의 결과 — 항목마다 저장에 필요한 것들. 검사를 두 번 돌지 않으려고 들고 간다. */
    private record Checked(Stock stock, BigDecimal refClose, List<ResearchPoint> points) {}

    /**
     * 접수 창 검사. {@code status} 만 보지 않고 시각도 본다 — 크론이 OPEN 으로 올린 뒤 {@code sealsAt}
     * 이 지나도 봉인 tx 가 확정되기 전까지는 상태가 아직 OPEN 이라, 그 틈에 들어온 등록을 막아야 한다.
     */
    private static void requireOpen(PredictionEvent event) {
        Instant now = Instant.now();
        if (event.getStatus() != PredictionEvent.Status.SCHEDULED
                && event.getStatus() != PredictionEvent.Status.OPEN) {
            throw new BusinessException(ErrorCode.EVENT_SEALED);
        }
        if (event.getStatus() != PredictionEvent.Status.OPEN || now.isBefore(event.getOpensAt())) {
            throw new BusinessException(ErrorCode.EVENT_NOT_OPEN);
        }
        if (!now.isBefore(event.getSealsAt())) {
            throw new BusinessException(ErrorCode.EVENT_SEALED);
        }
    }

    /**
     * 예측 기간 = 기준일 → 판정일 캘린더 일수. 이번 회차는 목→월이라 4다.
     *
     * <p>상수로 박지 않는 이유 — 다음 회차가 다른 요일이면 조용히 틀린 만기일이 박힌다. 값은 이미
     * 이벤트 행에 있다. DB CHECK(REAL 3~90)도 이 값을 본다.
     */
    private static short horizonOf(PredictionEvent event) {
        long days = ChronoUnit.DAYS.between(event.getBaseDate(), event.getSettleDate());
        return (short) days;
    }

    /**
     * 등록 직후의 내 참가 요약. 홈을 다시 부르지 않아도 화면이 "몇 종목 걸었고 몇 개 남았나" 를 안다 —
     * 방금 만든 배팅은 PENDING 이라 홈의 풀 집계에는 아직 안 보이고, 그러면 사용자 눈에는 아무 일도
     * 일어나지 않은 것처럼 보인다.
     *
     * <p>{@code paid}·{@code net}·{@code rank} 는 정산 뒤의 값이라 여기서는 늘 null 이다.
     */
    private static Me meOf(
            PredictionEvent event,
            List<PredictionEventBet> already,
            List<EventEntryCreateRequest.Item> added,
            int stockCountInEvent) {
        int stockCount = already.size() + added.size();
        BigInteger staked = BigInteger.ZERO;
        for (PredictionEventBet bet : already) {
            staked = staked.add(bet.getStake());
        }
        for (EventEntryCreateRequest.Item item : added) {
            staked = staked.add(item.stake());
        }
        return new Me(
                stockCount,
                staked,
                stockCount >= event.getMinStocks(),
                Math.max(0, stockCountInEvent - stockCount),
                null,
                null,
                null);
    }

    /**
     * 실패한 배팅 재전송 (§0.3) — 크론이 부른다.
     *
     * <p><b>반드시 새 작업으로 보낸다.</b> 인덱서 ⑤ 는 "배팅은 PENDING 인데 {@code operation_id} 의
     * 작업이 FAILED" 를 보고 배팅을 FAILED 로 되돌린다. 상태만 PENDING 으로 돌리고 작업 id 를 옛 것에
     * 두면 인덱서가 즉시 다시 FAILED 로 만들어 무한 재전송이 된다. {@code retry()} 가 작업 id 교체 ·
     * PENDING · {@code attempts++} 를 한 번에 한다.
     *
     * <p>횟수 상한은 두지 않는다 — 잔액이 모자라 실패한 사용자가 충전하면 다음 회차에 나가야 하고,
     * 창은 {@code sealsAt} 이 닫는다.
     */
    public int retryFailedBets() {
        List<EventBetDispatcher.Bet> sends = tx.execute(s -> reissue());
        dispatcher.dispatchBets(sends);
        return sends.size();
    }

    private List<EventBetDispatcher.Bet> reissue() {
        List<PredictionEventBet> retryable = bets.findRetryable(Instant.now());
        List<EventBetDispatcher.Bet> sends = new ArrayList<>(retryable.size());
        for (PredictionEventBet bet : retryable) {
            String wallet = bet.getUser().getWalletAddress();
            if (wallet == null || wallet.isBlank()) {
                // 등록 뒤에 지갑을 끊은 경우. 보낼 곳이 없으니 FAILED 로 둔다.
                continue;
            }
            Operation operation =
                    operationService.accept(
                            bet.getUser(),
                            Operation.Kind.EVENT_BET,
                            Operation.ResourceType.PREDICTION,
                            bet.getPredictionId());
            bet.retry(operation.getId());
            sends.add(new EventBetDispatcher.Bet(
                    operation.getId(),
                    bet.getEvent().getId(),
                    wallet,
                    bet.getEventStock().getIdx(),
                    bet.getStake(),
                    bet.getEventCommit()));
            log.info(
                    "[EVENT] 배팅 {} 재전송 {}회차 — 새 작업 {}",
                    bet.getPredictionId(),
                    bet.getAttempts(),
                    operation.getId());
        }
        return sends;
    }

    /**
     * 멱등 재생용 조회. 같은 {@code Idempotency-Key} 로 다시 들어온 요청은 접수를 되풀이하지 않고,
     * 저장해 둔 예측 id 목록으로 이 메서드를 타 같은 202 를 받는다.
     *
     * <p>멱등 저장소에 응답 전체가 아니라 <b>예측 id 목록</b>만 담는다 — 나머지(종목코드·커밋·작업 id)는
     * 배팅 행이 들고 있어 되짚을 수 있고, 응답을 통째로 Redis 에 넣으면 본문이 커진다.
     *
     * <p>{@code entries} 는 <b>이번 묶음만</b>이다. 내 배팅 전부를 내리면 두 번째 제출의 재요청이 첫
     * 제출까지 함께 돌려주어 "방금 뭘 넣었나" 가 달라진다. 반대로 {@code me} 는 누적이다 — 그건 현재
     * 참가 상태를 묻는 칸이다.
     */
    @Transactional(readOnly = true)
    public EventEntryCreateResponse accepted(Long userId, Long eventId, List<Long> predictionIds) {
        List<EventEntryCreateResponse.Entry> entries = new ArrayList<>(predictionIds.size());
        for (Long predictionId : predictionIds) {
            PredictionEventBet bet =
                    bets.findById(predictionId)
                            .filter(b -> b.getUser().getId().equals(userId))
                            .filter(b -> b.getEvent().getId().equals(eventId))
                            .orElseThrow(
                                    () -> new BusinessException(ErrorCode.PREDICTION_NOT_FOUND, "predictionId"));
            entries.add(new EventEntryCreateResponse.Entry(
                    bet.getEventStock().getStock().getCode(),
                    bet.getPredictionId(),
                    bet.getEventCommit(),
                    bet.getOperationId()));
        }

        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
        List<PredictionEventBet> mine = bets.findMine(eventId, userId);
        BigInteger staked = BigInteger.ZERO;
        for (PredictionEventBet bet : mine) {
            staked = staked.add(bet.getStake());
        }
        int stockCountInEvent = eventStocks.findCardsByEventId(eventId).size();
        return new EventEntryCreateResponse(
                eventId,
                entries,
                new Me(
                        mine.size(),
                        staked,
                        mine.size() >= event.getMinStocks(),
                        Math.max(0, stockCountInEvent - mine.size()),
                        null,
                        null,
                        null));
    }

    /**
     * 서명 대상 (§6). 항목마다 한 줄이고 순서는 요청의 {@code items} 순서다.
     *
     * <pre>
     * antenna:event-entry:v1
     * eventId=1
     * 005930/UP/93000.00/0x…/1000
     * 000660/DOWN/210000.00/0x…/500
     * chainId=31221
     * nonce=…
     * </pre>
     *
     * <p>목표가 표기는 {@link CommitPayload#formatPrice} 를 쓴다 — 서버와 프론트가 같은 문자열을
     * 만들어야 하고, 포맷 규칙이 두 군데면 {@code 93000} 과 {@code 93000.00} 이 갈려 서명만 어긋난다.
     *
     * <p>온체인 커밋({@code EventCommits})은 <b>서명에 안 들어간다.</b> 프론트가 그걸 계산하려면
     * {@code abi.encodePacked} 를 브라우저에서 재현해야 하는데, 그 값은 서버가 같은 재료
     * ({@code eventId}·{@code idx}·방향·{@code noteHash})로 만들 수 있다 — 넷 다 이미 서명 안에 있다.
     *
     * <p>{@code private} 이 아닌 이유 — 고정 벡터 테스트({@code EventEntrySignatureFixtureTest})가 이
     * 문자열을 바이트 단위로 붙든다. 프론트가 같은 문자열을 만들지 못하면 금요일 13:30 에 모든 서명이
     * 401 이 되고, 그때는 고칠 시간이 없다.
     */
    record Signed(Long eventId, List<String> lines, String signature) implements WalletSigned {

        static Signed of(Long eventId, List<EventEntryCreateRequest.Item> items, String signature) {
            List<String> lines = new ArrayList<>(items.size());
            for (EventEntryCreateRequest.Item item : items) {
                lines.add(item.stockCode()
                        + "/" + item.direction().name()
                        + "/" + CommitPayload.formatPrice(item.targetPrice())
                        + "/" + CommitHashes.noteHash(item.noteOrEmpty(), item.noteSalt())
                        + "/" + item.stake());
            }
            return new Signed(eventId, lines, signature);
        }

        @Override
        public SignatureScope scope() {
            return SignatureScope.EVENT_ENTRY;
        }

        @Override
        public String signingPayload(String nonce, long chainId) {
            return "antenna:" + scope().tag() + ":v1\n"
                    + "eventId=" + eventId + "\n"
                    + String.join("\n", lines)
                    + "\nchainId=" + chainId
                    + "\nnonce=" + nonce;
        }
    }
}
