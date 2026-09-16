package ssafy.a507.backend.domain.event.service;

import java.math.BigDecimal;
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
import ssafy.a507.backend.domain.chain.service.OperationService;
import ssafy.a507.backend.domain.event.dto.EventCreateRequest;
import ssafy.a507.backend.domain.event.dto.EventCreateResponse;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventStock;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
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
 * <p><b>R1 단계에서는 온체인 전송을 하지 않는다</b>(§0.2 ②). 이벤트·종목·기준가·작업(PENDING)까지
 * 만들고, {@code PoolRelayer.openEvent} 가 붙으면 커밋 뒤 트랜잭션 밖에서 잇는다. 그래서 지금은
 * {@code @Transactional} 을 붙여도 된다 — 전송이 생기는 순간 {@code AdService} 처럼 손으로 나눠야 한다.
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
    private final OperationService operationService;
    private final EventBetDispatcher dispatcher;
    /** 봉인은 접수(작업 행)와 전송을 손으로 나눈다 — 생성과 달리 tx 가 실제로 나간다. */
    private final TransactionTemplate tx;

    /**
     * 이벤트 생성 — 이벤트 행 + 종목 10개 + 기준가 복사 + {@code EVENT_ADMIN} 작업(PENDING).
     *
     * <p>기준가는 {@code daily_quotes(종목, baseDate).close} 를 복사한다. <b>없으면 null 로 두고
     * 응답에 그대로 보여준다</b> — 접수 시작이 배치 30분 뒤라 기다릴 수 없고, 관리자가
     * {@code PUT …/base-price} 로 채운다. 하나라도 비면 크론이 OPEN 으로 올리지 않는다.
     */
    @Transactional
    public EventCreateResponse create(Long adminUserId, EventCreateRequest request) {
        User admin = requireAdmin(adminUserId);
        validate(request);

        PredictionEvent event =
                events.save(PredictionEvent.create(
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

        // 온체인 openEvent 를 가리킬 작업. R1 은 PENDING 까지만 만들고 전송은 릴레이어가 붙은 뒤다.
        Operation operation =
                operationService.accept(
                        admin, Operation.Kind.EVENT_ADMIN, Operation.ResourceType.EVENT, event.getId());

        return new EventCreateResponse(event.getId(), operation.getId(), saved);
    }

    /** 기준가 수동 입력. 배치가 늦거나 실패했을 때 쓰는 길이다(§1 "없으면 관리자가 넣는다"). */
    @Transactional
    public void fixBasePrice(Long adminUserId, Long eventId, short idx, BigDecimal basePrice) {
        requireAdmin(adminUserId);
        if (!events.existsById(eventId)) {
            throw new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId");
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
