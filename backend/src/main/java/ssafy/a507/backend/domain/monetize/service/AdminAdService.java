package ssafy.a507.backend.domain.monetize.service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.entity.Notification;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.NotificationRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.service.AvailableBalanceService;
import ssafy.a507.backend.domain.chain.service.OperationService;
import ssafy.a507.backend.domain.chain.service.TokenOperationService;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdApproveResponse;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdminAdItem;
import ssafy.a507.backend.domain.monetize.dto.AdminAdDtos.AdminAdListResponse;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;

/**
 * 광고 승인·반려 (ANT-ADMIN-03). <b>돈이 나가는 자리</b>다.
 *
 * <p>승인은 {@link AdService#create} 가 예전에 하던 "접수 커밋 → 트랜잭션 밖에서 소각 전송" 을 그대로 넘겨받았다
 * ({@link TokenOperationService} 의 호출자 계약, ANT-CHAIN-12): ① 체인이 꺼져 있으면 아무것도 쓰기 전에 503
 * ② 승인(검사 · 기간 재계산 · 작업)을 커밋 ③ 트랜잭션 밖에서 소각 전송. DB 커밋이 먼저인 이유는 반대로 하면
 * "게재료는 탔는데 승인 기록이 없는" 상태가 생기고 그건 되돌릴 수단이 없어서다. 그래서 {@link #approve} 에는
 * {@code @Transactional} 이 없다.
 *
 * <p>③ 이 실패해도(잔액 부족 409 · RPC 503) <b>배너는 PENDING 그대로</b>다. 작업만 FAILED 로 닫힌다. 다시 누르면
 * 새 작업으로 다시 보낸다 — 사용자가 다시 신청하고 다시 서명하게 만들지 않는다. 승인 시 소각(tx 한 번)을 고른 이유가
 * 이것이다: 실패해도 아무 일 없었던 것과 같다. 선차감 후 환불은 환불 tx 가 실패하면 돈이 사라진 채 남는다.
 *
 * <p>권한은 서비스 안에서 본다({@code AdminModerationService} 와 같은 관례, ANT-ADMIN-01).
 */
@Service
@RequiredArgsConstructor
public class AdminAdService {

    /** 신청자에게 가는 알림 종류. 광고주가 자기 신청의 결과를 알 길이 이것뿐이다(2026-09-22 QA). */
    static final String AD_APPROVED = "AD_APPROVED";
    static final String AD_REJECTED = "AD_REJECTED";

    private final AdBannerRepository banners;
    private final UserRepository users;
    private final NotificationRepository notifications;
    private final AdService adService;
    private final CurrentUserProvider currentUserProvider;
    private final OperationService operationService;
    private final TokenOperationService tokenOperationService;
    private final AvailableBalanceService availableBalance;
    private final AdProperties properties;
    private final TransactionTemplate tx;

    /** 상태를 안 고르면 전 상태. 널을 넘기지 않는다 — PostgreSQL 이 널 파라미터 타입을 못 정한다(ANT-ADMIN-01 교훈). */
    @Transactional(readOnly = true)
    public AdminAdListResponse list(AdBanner.Status status) {
        requireAdmin();
        List<AdBanner.Status> statuses =
                status == null ? Arrays.asList(AdBanner.Status.values()) : List.of(status);

        // 같은 신청자가 여러 건이면 체인 조회는 한 번만. eth_call 이라 tx 는 없지만 네트워크다.
        Map<Long, String> availableByUser = new HashMap<>();
        List<AdminAdItem> items = new ArrayList<>();
        for (AdBanner b : banners.findForAdmin(statuses)) {
            String available = null;
            if (b.isPending()) {
                available = availableByUser.computeIfAbsent(
                        b.getAdvertiser().getId(), id -> availableOrNull(b.getAdvertiser()));
            }
            items.add(AdminAdItem.of(b, available, adService.burnInFlight(b.getId())));
        }
        return new AdminAdListResponse(items);
    }

    /** 승인 — 게재료 소각 tx 를 보낸다. 202. 확정(ACTIVE)은 인덱서 몫이다. */
    public AdApproveResponse approve(Long adId) {
        tokenOperationService.requireEnabled();
        Prepared prepared = tx.execute(s -> prepare(adId));
        // 실패하면 TokenOperationService 가 작업을 FAILED 로 닫고 예외를 던진다. 배너는 손대지 않는다 — PENDING 으로
        // 남아 자리와 홀드를 유지하고, 관리자가 다시 누르면 새 작업으로 다시 보낸다. 기간은 그때 다시 잡힌다.
        tokenOperationService.dispatchBurn(prepared.operationId(), prepared.price(), TokenReason.AD_PAY);
        // 전송이 나간 뒤에야 알린다 — 앞에서 409 로 끝나면 "승인됐다" 는 알림만 남는다.
        tx.executeWithoutResult(s -> notifications.save(Notification.create(
                users.getReferenceById(prepared.advertiserId()),
                AD_APPROVED,
                "광고가 승인됐습니다",
                "게재료를 소각하는 중입니다. 확정되면 홈 배너에 게재됩니다.",
                "/ads/new")));
        return new AdApproveResponse(prepared.operationId(), adId, Operation.Status.PENDING);
    }

    /**
     * 승인 접수. 검사 순서는 <b>권한 → 상태 → 잔액 → 슬롯</b>. 앞의 셋은 상태를 안 바꾸고, 슬롯은 기간 재계산 뒤에야
     * 볼 수 있어 마지막이다.
     */
    private Prepared prepare(Long adId) {
        requireAdmin();
        AdBanner banner =
                banners.findWithAdvertiser(adId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.AD_NOT_FOUND));
        if (!banner.isPending() || adService.burnInFlight(adId)) {
            throw new BusinessException(ErrorCode.AD_ALREADY_HANDLED);
        }

        User advertiser = banner.getAdvertiser();
        BigInteger price = banner.getPriceWei();
        // 가용잔액에 이 광고 자신의 홀드를 되돌려 본다. 안 그러면 자기 돈을 자기가 막아 영원히 승인이 안 된다.
        // 홀드 덕에 여기서 모자라는 경우는 드물다 — 사용자가 지갑에서 직접 뭔가 했을 때뿐이다.
        if (availableBalance.of(advertiser).add(price).compareTo(price) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }

        // 게재 기간을 지금부터 다시 잡는다 — 승인이 늦어진 만큼 광고주가 손해를 보면 안 된다.
        Instant now = Instant.now();
        Instant endsAt = now.plus(banner.days(), ChronoUnit.DAYS);
        // 기간이 밀렸으니 겹침이 달라졌을 수 있다. 자기 자신은 뺀다.
        if (banners.countOccupying(AdService.OCCUPYING, now, endsAt, adId) >= properties.slotCount()) {
            throw new BusinessException(ErrorCode.AD_SLOT_SOLD_OUT);
        }
        banner.approve(now);

        Operation operation =
                operationService.accept(advertiser, Operation.Kind.AD, Operation.ResourceType.AD, adId);
        return new Prepared(operation.getId(), price, advertiser.getId());
    }

    /** 반려 — 아무것도 태우지 않는다. 배너를 닫으면 자리와 가용잔액 홀드가 풀린다. */
    @Transactional
    public void reject(Long adId) {
        requireAdmin();
        AdBanner banner =
                banners.findById(adId).orElseThrow(() -> new BusinessException(ErrorCode.AD_NOT_FOUND));
        if (!banner.isPending() || adService.burnInFlight(adId)) {
            throw new BusinessException(ErrorCode.AD_ALREADY_HANDLED);
        }
        banner.reject();
        notifications.save(Notification.create(
                banner.getAdvertiser(),
                AD_REJECTED,
                "광고 신청이 반려됐습니다",
                "묶여 있던 게재료가 가용잔액으로 돌아왔습니다. 배너나 링크를 고쳐 다시 신청할 수 있습니다.",
                "/ads/new"));
    }

    /** 목록용. 체인이 꺼져 있거나 지갑이 없으면 값 대신 null — 목록 전체가 503 이 되면 안 된다. */
    private String availableOrNull(User advertiser) {
        try {
            return availableBalance.of(advertiser).toString();
        } catch (BusinessException e) {
            return null;
        }
    }

    private User requireAdmin() {
        User user =
                users.findById(currentUserProvider.currentUserId())
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.getRole() != User.Role.ADMIN) {
            throw new BusinessException(ErrorCode.ADMIN_ONLY);
        }
        return user;
    }

    /** 승인 트랜잭션이 커밋한 것 중 소각 전송에 필요한 둘. */
    private record Prepared(String operationId, BigInteger price, Long advertiserId) {}
}
