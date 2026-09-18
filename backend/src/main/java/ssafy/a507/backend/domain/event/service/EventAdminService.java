package ssafy.a507.backend.domain.event.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.service.EventSettlementRunner;
import ssafy.a507.backend.domain.chain.service.EventSettlementService;
import ssafy.a507.backend.domain.chain.service.OperationService;
import ssafy.a507.backend.domain.event.dto.EventCreateRequest;
import ssafy.a507.backend.domain.event.dto.EventCreateResponse;
import ssafy.a507.backend.domain.event.dto.EventPhase;
import ssafy.a507.backend.domain.event.dto.EventSettleResponse;
import ssafy.a507.backend.domain.event.dto.EventSettlementStatusResponse;
import ssafy.a507.backend.domain.event.entity.EventSettlementRun;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventBet;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.EventSettlementRunRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventStockRepository;
import ssafy.a507.backend.domain.market.entity.DailyQuote;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.market.repository.DailyQuoteRepository;
import ssafy.a507.backend.domain.market.repository.StockRepository;

/**
 * 이벤트 관리자 — ANT-EVENT-04, 작업명세 v1.2 §4.
 *
 * <p><b>권한은 여기서 본다</b>({@code role != ADMIN → EVENT_ADMIN_ONLY}). 매처나
 * {@code @PreAuthorize} 가 아닌 이유는 §0.2 ④ — JWT 의 {@code role} 클레임을 Spring 권한으로 바꾸는
 * 컨버터가 없어 그 둘은 {@code SecurityConfig}(공용) 수정이 함께 필요하고, 기존 관례도 서비스 안
 * 비교다({@code SeasonJoinService} 의 DEMO 시즌).
 *
 * <p><b>온체인 전송이 있는 메서드에는 {@code @Transactional} 이 없다</b>({@link EventBetDispatcher} 의
 * 호출자 계약). 생성과 봉인이 그렇다 — 행을 먼저 커밋하고 tx 를 보낸다. 붙이면 디스패처가
 * {@code IllegalStateException} 으로 바로 터뜨린다. 기준가 입력만 전송이 없어 그대로 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventAdminService {

    private final UserRepository users;
    private final StockRepository stocks;
    private final DailyQuoteRepository dailyQuotes;
    private final PredictionEventRepository events;
    private final PredictionEventStockRepository eventStocks;
    private final PredictionEventBetRepository bets;
    private final PredictionEventResultRepository results;
    private final EventSettlementRunRepository runs;
    private final OperationService operationService;
    private final EventBetDispatcher dispatcher;
    private final EventSettlementRunner settlementRunner;
    private final EventResultService resultService;
    /** 봉인은 접수(작업 행)와 전송을 손으로 나눈다 — 생성과 달리 tx 가 실제로 나간다. */
    private final TransactionTemplate tx;

    /**
     * 정산 러너를 돌리는 스레드 하나. {@code NewsIngestScheduler} 의 관련도 채점과 같은 모양이다.
     *
     * <p>하나인 이유 — 러너는 릴레이어 키 하나로 tx 를 순서대로 보낸다. 둘이 동시에 돌면 nonce 가
     * 엉킨다. 회차가 여럿이어도 줄을 세우는 편이 맞다(러너 자체에도 같은 이벤트 잠금이 있다).
     *
     * <p>데몬이다 — 재배포로 앱이 내려갈 때 돌던 정산이 JVM 종료를 붙잡지 않는다. 끊긴 정산은 실행
     * 행이 미완으로 남고, 다시 누르면 러너가 온체인 상태를 읽어 끝난 단계를 건너뛰고 이어 간다.
     */
    private final ExecutorService settlementExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "event-settlement");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 이벤트 생성 — 이벤트 행 + 종목 10개 + 기준가 복사 + {@code EVENT_ADMIN} 작업, 그리고 온체인 {@code openEvent}.
     *
     * <p>기준가는 {@code daily_quotes(종목, baseDate).close} 를 복사한다. <b>없으면 null 로 두고
     * 응답에 그대로 보여준다</b> — 접수 시작이 배치 30분 뒤라 기다릴 수 없고, 관리자가
     * {@code PUT …/base-price} 로 채운다. 하나라도 비면 크론이 OPEN 으로 올리지 않는다.
     *
     * <p><b>{@code openEvent} 를 여기서 보낸다.</b> 안 보내면 인덱서가 {@code EventOpened} 를 볼 일이
     * 없어 {@code open_tx_hash} 가 영영 null 이고, 크론의 세 조건 중 하나가 안 차 이벤트가 열리지
     * 않는다 — 대회에 아무도 들어올 수 없다. ANT-EVENT-04 는 릴레이어가 없어 이 전송을 미뤘는데
     * (§0.2 ②), ANT-EVENT-02 가 머지되면서 {@code PoolRelayer} 가 생겼다.
     */
    public EventCreateResponse create(Long adminUserId, EventCreateRequest request) {
        Accepted accepted = tx.execute(s -> accept(adminUserId, request));
        dispatcher.dispatchOpen(
                accepted.operationId(),
                accepted.response().eventId(),
                accepted.response().stocks().size(),
                request.minStake(),
                request.maxStake());
        return accepted.response();
    }

    /** 접수 결과 — 응답과 전송할 작업. 트랜잭션 밖으로 나가는 값이라 엔티티를 담지 않는다. */
    private record Accepted(EventCreateResponse response, String operationId) {}

    private Accepted accept(Long adminUserId, EventCreateRequest request) {
        User admin = requireAdmin(adminUserId);
        validate(request);
        /* UNIQUE 제약이 막긴 하지만 그건 DataIntegrityViolation → 500 이다. 관리자가 slug 를
           겹쳐 적은 것은 서버 버그가 아니라 입력 실수라 409 로 돌려준다. */
        if (request.slug() != null && events.existsBySlug(request.slug())) {
            throw new BusinessException(ErrorCode.EVENT_DUPLICATE_SLUG, "slug");
        }

        PredictionEvent event =
                events.save(PredictionEvent.create(
                        request.slug(),
                        request.title(),
                        request.opensAt(),
                        request.sealsAt(),
                        request.baseDate(),
                        request.settleDate(),
                        request.minStake(),
                        request.maxStake(),
                        request.minStocks()));

        List<EventCreateResponse.Stock> saved = new ArrayList<>(request.stockCodes().size());
        short idx = 0;
        for (String code : request.stockCodes()) {
            Stock stock =
                    stocks.findById(code)
                            .filter(Stock::isListed)
                            .orElseThrow(() -> new BusinessException(ErrorCode.STOCK_NOT_FOUND, "stockCodes"));
            BigDecimal basePrice =
                    dailyQuotes
                            .findByStock_CodeAndTradeDate(code, request.baseDate())
                            .map(DailyQuote::getClose)
                            .orElse(null);
            eventStocks.save(PredictionEventStock.of(event, idx, stock, basePrice));
            saved.add(new EventCreateResponse.Stock(idx, code, basePrice));
            idx++;
        }

        Operation operation =
                operationService.accept(
                        admin, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, event.getId());

        return new Accepted(
                new EventCreateResponse(event.getId(), operation.getId(), saved), operation.getId());
    }

    /**
     * {@code openEvent} 재전송 — 생성 때 전송이 못 나갔을 때의 길.
     *
     * <p>있어야 하는 이유: 전송이 실패해도 이벤트 행은 남는데, 다시 만들면 종목 칸의 {@code idx} 가
     * 달라져 이미 걸린 배팅의 커밋과 어긋난다. 실패한 이벤트를 버리는 대신 같은 행에 다시 보낸다.
     *
     * <p>이미 {@code open_tx_hash} 가 있으면 보내지 않는다 — 컨트랙트가 {@code BadState} 로 거부한다.
     */
    public void openNow(Long adminUserId, Long eventId) {
        record ToSend(String operationId, int stockCount, BigInteger minStake, BigInteger maxStake) {}
        ToSend send =
                tx.execute(s -> {
                    User admin = requireAdmin(adminUserId);
                    PredictionEvent event =
                            events.findById(eventId)
                                    .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
                    if (event.getOpenTxHash() != null) {
                        throw new BusinessException(ErrorCode.EVENT_NOT_OPEN, "eventId");
                    }
                    List<PredictionEventStock> cards = eventStocks.findCardsByEventId(eventId);
                    if (cards.isEmpty()) {
                        throw new BusinessException(ErrorCode.EVENT_STOCK_NOT_INCLUDED, "eventId");
                    }
                    Operation operation =
                            operationService.accept(
                                    admin, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, eventId);
                    return new ToSend(
                            operation.getId(), cards.size(), event.getMinStake(), event.getMaxStake());
                });
        dispatcher.dispatchOpen(
                send.operationId(), eventId, send.stockCount(), send.minStake(), send.maxStake());
    }

    /**
     * 기준가 수동 입력. 배치가 늦거나 실패했을 때 쓰는 길이다(§1 "없으면 관리자가 넣는다").
     *
     * <p><b>SCHEDULED 일 때만 받는다.</b> 접수가 열린 뒤에는 참가자가 그 기준가를 보고 걸었고, 봉인 뒤에는 그 값이
     * 그대로 {@code resolveStock} 으로 체인에 올라간다 — 여기서 바꾸면 이미 걸린 배팅의 승패가 뒤집힌다(2026-09-18).
     */
    @Transactional
    public void fixBasePrice(Long adminUserId, Long eventId, short idx, BigDecimal basePrice) {
        requireAdmin(adminUserId);
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
        if (event.getStatus() != PredictionEvent.Status.SCHEDULED) {
            throw new BusinessException(ErrorCode.EVENT_BASE_PRICE_LOCKED, "eventId");
        }
        PredictionEventStock stock =
                eventStocks
                        .findByEventIdAndIdx(eventId, idx)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.EVENT_STOCK_NOT_INCLUDED, "idx"));
        stock.fixBasePrice(basePrice);
    }

    /**
     * 봉인 — 수동 길 (작업명세 v1.2 §3-8). 평소에는 {@code EventClockScheduler} 가 {@code seals_at} 을
     * 보고 부르고, 크론이 못 돌았거나 일찍 닫아야 할 때 관리자가 이 API 로 부른다.
     */
    public void seal(Long adminUserId, Long eventId) {
        tx.executeWithoutResult(s -> requireAdmin(adminUserId));
        sealNow(eventId, adminUserId);
    }

    /**
     * 봉인 — 크론 길. 권한 검사가 없는 대신 호출자가 {@code EventClockScheduler} 하나뿐이다.
     *
     * <p>작업 행의 사용자 칸에는 관리자 계정을 넣는다. 크론에는 로그인 주체가 없는데
     * {@code operations.user_id} 는 NOT NULL 이라 누군가는 있어야 하고, 이 작업은 사용자의 돈이 아니라
     * 운영 행위라 누가 들어가든 뜻이 같다. 관리자가 한 명도 없으면 보내지 않고 로그만 남긴다 —
     * 데이터가 덜 갖춰진 환경(로컬·테스트)에서 크론이 매분 터지게 두지 않는다.
     */
    public void sealSystem(Long eventId) {
        Long actorId =
                users.findFirstByRoleOrderByIdAsc(User.Role.ADMIN).map(User::getId).orElse(null);
        if (actorId == null) {
            log.warn("[EVENT] {} 봉인 건너뜀 — 관리자 계정이 없다", eventId);
            return;
        }
        sealNow(eventId, actorId);
    }

    /**
     * 봉인 tx 전송. <b>상태는 바꾸지 않는다</b> — SEALED 전이는 인덱서가 {@code EventSealed} 를 보고
     * 한다(§0.3). 여기서 미리 SEALED 로 적으면 전송이 실패했을 때 체인은 열려 있는데 서비스만 닫힌다.
     *
     * <p>{@code @Transactional} 이 없는 이유는 {@link EventBetDispatcher} 의 호출자 계약이다 — 작업 행을
     * 먼저 커밋하고 그 뒤에 보낸다.
     *
     * @param triggeredBy 작업 행의 사용자 칸. 크론이 부를 때는 이벤트를 만든 관리자가 아니라 지정된
     *     운영 계정이 없으므로 호출자가 정한다
     */
    public void sealNow(Long eventId, Long triggeredBy) {
        String operationId =
                tx.execute(s -> {
                    PredictionEvent event =
                            events.findById(eventId)
                                    .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
                    if (event.getStatus() != PredictionEvent.Status.OPEN) {
                        throw new BusinessException(ErrorCode.EVENT_NOT_OPEN, "eventId");
                    }
                    User actor =
                            users.findById(triggeredBy)
                                    .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
                    return operationService
                            .accept(actor, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, eventId)
                            .getId();
                });
        dispatcher.dispatchSeal(operationId, eventId);
    }

    /**
     * 정산 트리거 — 화요일 13:30 판정 배치 뒤 관리자가 누른다 (작업명세 v1.2 §3-9).
     *
     * <p><b>비동기다.</b> 러너는 reveal → resolve ×10 → settle → sweep 을 순서대로 보내고 receipt 를
     * 기다려 몇 분이 걸린다. 요청 스레드에서 끝까지 붙들면 게이트웨이가 먼저 끊고, 그러면 관리자는
     * 정산이 돌고 있는지 죽었는지 알 수 없다. 진행은 {@link #settlementStatus} 로 본다.
     *
     * <p>전제는 <b>누르기 전에</b> 본다. 러너 안에서도 다시 보지만, 여기서 걸러야 "안 될 요청" 이
     * 실행 행을 남기지 않는다. {@code EVENT_NOT_READY} 는 코드만 나르므로 무엇이 빠졌는지는 상태
     * API 가 {@code reasons} 로 보여준다.
     *
     * <p>굳히기({@code freeze})는 러너가 끝난 <b>뒤</b> 여기서 부른다. 러너(chain 패키지)가 event
     * 패키지의 서비스를 부르면 방향이 거꾸로다 — 체인 층이 도메인 층을 모르게 둔다.
     */
    public EventSettleResponse settle(Long adminUserId, Long eventId) {
        tx.executeWithoutResult(s -> requireAdmin(adminUserId));
        if (!events.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId");
        }
        if (!settlementRunner.readiness(eventId).ready()) {
            throw new BusinessException(ErrorCode.EVENT_NOT_READY, "eventId");
        }

        settlementExecutor.submit(() -> {
            try {
                settlementRunner.run(eventId);
                resultService.freeze(eventId);
            } catch (RuntimeException e) {
                // 러너가 실행 행에 FAILED 로 남긴다 — 여기서 다시 던져 봐야 받을 사람이 없다.
                log.warn("[EVENT] {} 정산 실패 — {}", eventId, e.toString());
            }
        });

        return new EventSettleResponse(
                eventId, settlementRunner.state(eventId).map(EventSettlementRun::getId).orElse(null));
    }

    /**
     * 정산 상태 — 관리자 화면 (작업명세 v1.2 §3-10).
     *
     * <p>{@code runId} 가 {@link #settle} 응답에서 null 일 수 있다. 실행 행은 러너 스레드가 만드는데
     * 202 는 그 전에 나갈 수 있어서다 — 그래서 화면은 이 API 로 다시 묻는다. 202 에 runId 를 꼭 넣으려고
     * 요청 스레드에서 짧게 폴링하는 대신, 한 번 더 묻는 쪽을 골랐다. 어차피 진행을 보려면 이 API 를
     * 부른다.
     */
    @Transactional(readOnly = true)
    public EventSettlementStatusResponse settlementStatus(Long adminUserId, Long eventId) {
        requireAdmin(adminUserId);
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));

        EventSettlementService.Preflight pre = settlementRunner.readiness(eventId);
        Map<PredictionEventBet.Status, Long> counts = new EnumMap<>(PredictionEventBet.Status.class);
        long total = 0;
        for (Object[] row : bets.countByStatus(eventId)) {
            long n = ((Number) row[1]).longValue();
            counts.put((PredictionEventBet.Status) row[0], n);
            total += n;
        }

        return new EventSettlementStatusResponse(
                eventId,
                event.getStatus(),
                EventPhase.of(event.getStatus()),
                pre.ready(),
                pre.reasons(),
                runs.findLatest(eventId).map(EventSettlementStatusResponse.Run::of).orElse(null),
                new EventSettlementStatusResponse.Bets(
                        total,
                        counts.getOrDefault(PredictionEventBet.Status.PENDING, 0L),
                        counts.getOrDefault(PredictionEventBet.Status.ACTIVE, 0L),
                        counts.getOrDefault(PredictionEventBet.Status.FAILED, 0L),
                        counts.getOrDefault(PredictionEventBet.Status.SETTLED, 0L)
                                + counts.getOrDefault(PredictionEventBet.Status.REFUNDED, 0L)),
                results.findRanked(eventId).size());
    }

    private User requireAdmin(Long userId) {
        User user =
                users.findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.EVENT_ADMIN_ONLY);
        }
        return user;
    }

    /**
     * 모양 검증 — 종목 중복 없음 · 일정 순서.
     *
     * <p>종목 <b>개수는 여기서 고정하지 않는다.</b> 이번 회차가 10개일 뿐이고, 개수 규칙은
     * {@code min_stocks} 처럼 이벤트 행이 들고 있는 값이라 다음 회차에 바뀔 수 있다. 대신 비어 있는
     * 요청은 {@code @NotEmpty} 가 막고, 최소 참가 종목 수보다 적으면 여기서 막는다 — 그러면 아무도
     * 참가 조건을 채울 수 없는 이벤트가 만들어진다.
     */
    private void validate(EventCreateRequest request) {
        Set<String> unique = new HashSet<>(request.stockCodes());
        if (unique.size() != request.stockCodes().size()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "stockCodes");
        }
        if (request.stockCodes().size() < request.minStocks()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "stockCodes");
        }
        if (!request.opensAt().isBefore(request.sealsAt())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sealsAt");
        }
        if (request.minStake().compareTo(request.maxStake()) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "maxStake");
        }
        // 기준일은 접수 시작 날짜보다 뒤일 수 없다 — 기준가가 아직 없는 날을 기준으로 삼는 셈이 된다.
        if (request.baseDate().isAfter(
                request.opensAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "baseDate");
        }
        if (!request.settleDate().isAfter(request.baseDate())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "settleDate");
        }
    }
}
