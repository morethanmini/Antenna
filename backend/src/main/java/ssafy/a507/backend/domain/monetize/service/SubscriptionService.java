package ssafy.a507.backend.domain.monetize.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.common.security.WalletSigned;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.account.service.AvatarUrls;
import ssafy.a507.backend.domain.account.entity.UserInterest;
import ssafy.a507.backend.domain.account.repository.UserInterestRepository;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.service.AvailableBalanceService;
import ssafy.a507.backend.domain.chain.service.OperationService;
import ssafy.a507.backend.domain.chain.service.TokenOperationService;
import ssafy.a507.backend.domain.monetize.dto.MySubscriptionListResponse;
import ssafy.a507.backend.domain.monetize.dto.SubscribeRequest;
import ssafy.a507.backend.domain.monetize.dto.SubscribeResponse;
import ssafy.a507.backend.domain.monetize.entity.PublisherFee;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.PublisherFeeRepository;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;

/**
 * 유료 구독 결제 — ANT-TOKEN-01, 화면 E-02.
 *
 * <p>이 도메인에서 <b>구독 행을 만드는 유일한 자리</b>다. 여기가 없는 동안 게이팅 셋(리포트 잠금 ·
 * 예측 근거 · 증명)은 "구독자면 연다" 분기를 갖고 있었지만 그 분기에 들어갈 사용자가 존재할 수 없었다.
 *
 * <p><b>트랜잭션을 손으로 나눈다</b> — {@link TokenOperationService} 의 호출자 계약(ANT-CHAIN-12)이고
 * {@link AdService#create} 와 같은 모양이다: ① 체인이 꺼져 있으면 아무것도 쓰기 전에 503 ② 접수(검사 ·
 * 서명 · 구독 PENDING · 작업)를 커밋 ③ <b>트랜잭션 밖에서</b> 전송. DB 커밋이 먼저인 이유는 반대로
 * 하면 "돈은 나갔는데 구독이 없는" 상태가 생기고 그건 되돌릴 수단이 없어서다. 그래서 이 메서드에는
 * {@code @Transactional} 이 없다 — 붙이면 ③ 이 {@code IllegalStateException} 으로 거부된다.
 *
 * <p>70:30 분배는 컨트랙트가 한다. 릴레이어는 {@code subscribe(구독자, 예측가, 금액)} 한 번만 부른다.
 *
 * <p>확정은 토큰 인덱서 몫이다 — {@code TokenIndexService} 가 이벤트를 보고 PENDING → ACTIVE 로 올리고
 * {@code expires_at} 을 개시 +30일로 채운다. 이 메서드는 202 까지다.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionService {

    private final EntityManager em;
    private final UserRepository users;
    private final SubscriptionRepository subscriptions;
    private final PublisherFeeRepository fees;
    private final AvailableBalanceService availableBalance;
    private final OperationRepository operations;
    private final SignatureGuard signatureGuard;
    private final OperationService operationService;
    private final TokenOperationService tokenOperationService;
    private final TransactionTemplate tx;
    private final AvatarUrls avatarUrls;
    private final UserInterestRepository userInterests;
    private final PredictionRepository predictions;

    public SubscribeResponse subscribe(Long subscriberId, Long publisherId, SubscribeRequest request) {
        tokenOperationService.requireEnabled();
        Accepted accepted = tx.execute(status -> accept(subscriberId, publisherId, request));
        if (accepted.fee().signum() == 0) {
            // 무료 채널 — 접수 트랜잭션에서 이미 ACTIVE 로 닫혔다(accept 끝). 보낼 tx 가 없다.
            return new SubscribeResponse(
                    accepted.operationId(), accepted.subscriptionId(), Operation.Status.SUCCEEDED);
        }
        try {
            tokenOperationService.dispatchSubscribe(accepted.operationId(), publisherId, accepted.fee());
        } catch (RuntimeException e) {
            // 전송이 못 나갔으면 구독도 FAILED 로 닫는다. 안 닫으면 영원히 PENDING 인 행이 남아 중복 구독을
            // 막는 검사에 걸려, 사용자가 다시 시도할 수 없게 된다. 작업 자체는 TokenOperationService 가
            // FAILED 로 닫는다. (2026-09-19 전에는 expire() 로 닫아 화면에 "만료됨" 으로 보였다.)
            tx.executeWithoutResult(
                    s -> subscriptions.findById(accepted.subscriptionId()).ifPresent(Subscription::fail));
            throw e;
        }
        // 전송 뒤에도 PENDING 이다 — tx 해시만 붙었고 확정은 인덱서 몫이다.
        return new SubscribeResponse(
                accepted.operationId(), accepted.subscriptionId(), Operation.Status.PENDING);
    }

    /**
     * 접수. 순서가 <b>자기 구독 → 구독자 행 잠금 → 채널 존재 → 구독료 → 중복 → 잔액 → 서명</b> 인 것은
     * 의도적이다.
     *
     * <p><b>잠금이 검사보다 먼저다.</b> 중복 검사와 행 생성 사이가 열려 있어 같은 순간 두 요청이 둘 다
     * "기존 구독 없음" 을 보고 통과하면 같은 채널에 두 번 결제된다 — 토큰이 두 번 나가고 70:30 분배는
     * 되돌릴 수단이 없다. 구독 행을 잠글 수는 없다(아직 없는 행이라 {@code FOR UPDATE} 가 0행을 잠근다).
     * 그래서 두 요청이 반드시 거쳐가는 <b>구독자 행</b>을 대신 잠근다. 검사를 잠금 뒤로 미뤄야 두 번째
     * 요청이 첫 번째의 커밋을 본다 — 순서가 뒤집히면 잠금이 있으나 마나다.
     *
     * <p>{@link SignatureGuard#verify} 는 nonce 를 태운다. 서명을 먼저 검증하면 중복 구독으로 409 를
     * 받는 요청에서도 nonce 가 사라져 사용자가 지갑에서 다시 서명해야 한다. 앞의 판정들은 상태를 바꾸지
     * 않으므로 앞에 둔다. 거꾸로 서명 검증은 <b>행을 만들기 직전</b>이어야 한다 — 검증 없이
     * 만들면 남의 지갑으로 결제되는 구독을 만들 수 있다({@link AdService} 와 같은 판단).
     */
    private Accepted accept(Long subscriberId, Long publisherId, SubscribeRequest request) {
        // 자기 구독은 인자 비교라 DB 를 타지 않는다. 어차피 409 를 줄 요청에 행 잠금을 잡을 이유가 없어
        // 잠금보다 앞에 둔다(형제 둘은 이런 검사가 없어 잠금이 ① 이다).
        if (subscriberId.equals(publisherId)) {
            throw new BusinessException(ErrorCode.SELF_SUBSCRIBE, "userId");
        }
        // 구독자 행 잠금 — 이 사용자의 구독 접수를 직렬화한다. 아래 중복 검사가 이 뒤에 있어야 두 번째
        // 요청이 첫 번째가 커밋한 행을 보고 409 를 받는다(PredictionRegisterService · EventEntryService 와
        // 같은 판단). 남의 구독에는 영향이 없다. 잠금은 이 접수 트랜잭션이 커밋될 때 풀리므로 체인 전송을
        // 기다리지 않는다.
        // ponytail: 코드가 지키는 규칙이다. 구독 행을 만드는 경로가 여기 말고 더 생기면 그 경로도 같은
        // 잠금을 잡아야 한다. 자동 갱신(ANT-TOKEN-06)이 옛 행을 먼저 EXPIRED 로 내리는 모양으로 확정되면
        // 부분 유니크 제약으로 올리는 것이 맞다 — 그때는 DB 가 경로와 무관하게 지킨다.
        User subscriber = em.find(User.class, subscriberId, LockModeType.PESSIMISTIC_WRITE);
        if (subscriber == null) {
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        }
        User publisher =
                users.findById(publisherId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId"));

        BigInteger fee =
                fees.findCurrent(publisherId, Instant.now(), Limit.of(1))
                        .map(PublisherFee::getFee)
                        .orElseThrow(() -> new BusinessException(ErrorCode.FEE_NOT_SET, "userId"));

        // PENDING 도 막는다 — 확정을 기다리는 중에 한 번 더 누르면 같은 채널에 두 번 결제된다.
        // 만료(EXPIRED)·실패(FAILED)한 구독은 막지 않는다. 재구독·재시도는 정상이고, 그때는 새 행이 생긴다.
        // "막는 상태" 를 적는다 — 열린 상태를 빼는 식이면 다음 값을 추가할 때 또 막힌다(FAILED 가 그랬다).
        subscriptions
                .findLatestBetween(subscriberId, publisherId, Limit.of(1))
                .filter(s -> s.getStatus() == Subscription.Status.PENDING
                        || s.getStatus() == Subscription.Status.ACTIVE)
                .ifPresent(s -> {
                    throw new BusinessException(ErrorCode.ALREADY_SUBSCRIBED, "userId");
                });

        // 가용잔액 = 체인 잔액 − 전송 중인 내 돈(PENDING 배팅·구독·광고). ANT-TOKEN-11 — 예전엔 원장을 봤는데
        // 원장은 인덱서 지연이 있고, 셋이 서로의 PENDING 을 못 보면 광고 심사 중인 돈으로 구독이 된다.
        // 체인 잔액은 전송 시뮬레이션이 한 번 더 본다. subscriber 는 위에서 행 잠금과 함께 읽었다.
        if (availableBalance.of(subscriber).compareTo(fee) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        signatureGuard.verify(subscriberId, new Signed(publisherId, fee, request.signature()));

        Subscription subscription =
                subscriptions.save(Subscription.request(subscriber, publisher, fee));
        Operation operation =
                operationService.accept(
                        subscriber,
                        Operation.Kind.SUBSCRIBE,
                        Operation.ResourceType.SUBSCRIPTION,
                        subscription.getId());

        // 무료 채널(구독료 0, 온보딩 기본값)은 체인에 보내지 않고 여기서 확정한다. 컨트랙트 subscribe 가
        // amount == 0 을 ZeroAmount 로 되돌려, 보내면 매번 500 에 FAILED 구독이 쌓였다.
        // 작업·구독 행은 유료와 똑같이 남긴다 — 화면은 같은 폴링으로 곧바로 SUCCEEDED 를 본다.
        // ponytail: 발행자 "새 구독자" 알림은 인덱서(TokenIndexService.notifySubscribed)에만 있어 무료 구독은
        // 알리지 않는다. 자동 갱신(ANT-TOKEN-06)이 붙으면 그 배치도 0 이면 전송을 건너뛰어야 한다.
        if (fee.signum() == 0) {
            subscription.activate(null, Instant.now());
            operation.markSucceeded(null);
        }

        return new Accepted(operation.getId(), subscription.getId(), fee);
    }


    /**
     * 멱등 재생용 조회. 같은 키로 다시 들어온 요청은 접수를 되풀이하지 않고, 저장해 둔
     * {@code operationId} 로 이 메서드를 타 같은 202 를 받는다.
     *
     * <p>멱등 저장소에 <b>구독 id 가 아니라 작업 id 를 담는 이유</b> — 작업이 리소스를
     * ({@code resourceType}·{@code resourceId}) 가리키므로 작업 하나로 둘 다 복원된다. 반대로 담으면
     * 구독에서 작업을 되찾는 조회를 새로 만들어야 하는데, 그 방향 인덱스가 없다.
     *
     * <p>남의 작업은 404 다. 멱등 저장소가 사용자별이라 여기 남의 id 가 올 일은 없지만, 소유자 조건이
     * 없으면 나중에 이 메서드를 다른 곳에서 부를 때 구멍이 된다.
     */
    @Transactional(readOnly = true)
    public SubscribeResponse accepted(Long subscriberId, String operationId) {
        Operation operation =
                operations
                        .findById(operationId)
                        .filter(o -> o.getUser().getId().equals(subscriberId))
                        .filter(o -> o.getResourceType() == Operation.ResourceType.SUBSCRIPTION)
                        .orElseThrow(() -> new BusinessException(ErrorCode.OPERATION_NOT_FOUND, "operationId"));
        return new SubscribeResponse(operation.getId(), operation.getResourceId(), operation.getStatus());
    }

    /**
     * 내 구독 목록 — ANT-TOKEN-05, 화면 E-03. 만료분까지 최신순으로 전부 내린다.
     *
     * <p>결제(위)와 달리 체인을 타지 않는다. 그래도 같은 서비스에 둔 이유는 구독 행의 수명
     * (접수 → 확정 → 만료 → 갱신 해지)을 한 자리에서 읽게 하려는 것이다.
     */
    @Transactional(readOnly = true)
    public MySubscriptionListResponse mine(Long subscriberId) {
        List<Subscription> mine = subscriptions.findMine(subscriberId);
        if (mine.isEmpty()) {
            return new MySubscriptionListResponse(List.of());
        }

        /* 예측가에 딸린 값 셋을 각각 한 번에 읽는다(S15P21A507-273). 줄마다 찾으면 구독 수만큼
           쿼리가 나간다 — 같은 사람을 두 번 구독할 수는 없지만 목록은 얼마든지 길어질 수 있다. */
        List<User> publishers = mine.stream().map(Subscription::getPublisher).toList();
        List<Long> publisherIds = publishers.stream().map(User::getId).toList();

        Map<Long, String> avatars = avatarUrls.byUserId(publishers);
        Map<Long, List<String>> interests =
                userInterests.findByUserIdInOrderByUserIdAscIdAsc(publisherIds).stream()
                        .collect(Collectors.groupingBy(
                                row -> row.getUser().getId(),
                                Collectors.mapping(UserInterest::getSector, Collectors.toList())));
        Map<Long, Instant> latest =
                predictions.latestPredictionAt(publisherIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0], row -> (Instant) row[1]));

        return new MySubscriptionListResponse(mine.stream()
                .map(s -> {
                    Long id = s.getPublisher().getId();
                    return MySubscriptionListResponse.Item.of(
                            s,
                            new MySubscriptionListResponse.Publisher(
                                    id,
                                    s.getPublisher().getNickname(),
                                    avatars.get(id),
                                    /* 안 고른 사람은 표에 키가 없다. null 대신 빈 배열을 내려
                                       화면이 가드를 따로 쓰지 않게 한다(구독 목록의 items 와 같은 판단) */
                                    interests.getOrDefault(id, List.of()),
                                    latest.get(id)));
                })
                .toList());
    }

    /**
     * 자동 갱신 해지 — ANT-TOKEN-05, 화면 E-03.
     *
     * <p><b>구독을 끊지 않는다.</b> 만료일까지는 ACTIVE 그대로고 다음 주기 결제만 멈춘다
     * (설계서 §4 E-03). 명세에 되살리는 경로가 없어 화면이 확인 단계를 둔다.
     *
     * <p>남의 구독과 없는 구독을 같은 404 로 묶는다 — 403 을 주면 "그 id 의 구독은 있다" 가
     * 새 나간다.
     */
    @Transactional
    public void cancelRenewal(Long subscriberId, Long subscriptionId) {
        subscriptions
                .findById(subscriptionId)
                .filter(s -> s.getSubscriber().getId().equals(subscriberId))
                .orElseThrow(() -> new BusinessException(ErrorCode.SUBSCRIPTION_NOT_FOUND, "id"))
                .cancelRenewal(Instant.now());
    }

    private record Accepted(String operationId, Long subscriptionId, BigInteger fee) {}

    /**
     * 서명 대상. 요청 본문에는 서명뿐이고 {@code publisherId}·{@code fee} 는 서버가 채운다 — 구독료를
     * 요청으로 받으면 싸게 적어 보낸 값으로 결제된다.
     *
     * <p>그래도 <b>fee 가 서명에 들어간다</b>. 사용자가 화면에서 본 가격과 서버가 결제하는 가격이 같다는
     * 것을 지갑 서명으로 묶는 것이다. 서명 뒤 접수 전에 채널이 가격을 올렸다면 서명이 안 맞아 거절된다 —
     * 모르는 값으로 결제되는 것보다 낫다.
     *
     * <p>{@code operatorAuthorize} 는 자동 갱신용 위임 승인이다(명세 §4.2). 최초 구독 서명에 넣어 두면
     * 배치 B4(ANT-TOKEN-06)가 만료 때 다시 서명받지 않고 재결제할 수 있다. 지금은 <b>문자열에만
     * 들어간다</b> — 위임을 실제로 체인에 거는 경로는 확인되지 않았고(스펙에 조회만 있다) ANT-TOKEN-06
     * 의 몫이다. 여기서 빼 두면 나중에 서명 규격이 바뀌어 옛 구독과 갈린다.
     */
    private record Signed(Long publisherId, BigInteger fee, String signature) implements WalletSigned {

        @Override
        public SignatureScope scope() {
            return SignatureScope.SUBSCRIBE;
        }

        @Override
        public String signingPayload(String nonce, long chainId) {
            return "antenna:"
                    + scope().tag()
                    + ":v1\n"
                    + "publisherId="
                    + publisherId
                    + "\n"
                    + "fee="
                    + fee
                    + "\n"
                    + "operatorAuthorize=true\n"
                    + "chainId="
                    + chainId
                    + "\n"
                    + "nonce="
                    + nonce;
        }
    }
}
