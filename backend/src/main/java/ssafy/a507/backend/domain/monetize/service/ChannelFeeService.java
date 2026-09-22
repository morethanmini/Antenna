package ssafy.a507.backend.domain.monetize.service;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.text.KoreanText;
import ssafy.a507.backend.domain.account.entity.Notification;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.NotificationRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.account.service.NotificationGate;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeAppliedResponse;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeResponse.FeeChange;
import ssafy.a507.backend.domain.monetize.dto.ChannelFeeResponse;
import ssafy.a507.backend.domain.monetize.entity.PublisherFee;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.PublisherFeeRepository;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;

/**
 * 내 채널 구독료 — ANT-TOKEN-05, 화면 E-04(환경 설정 안의 '내 채널' 섹션).
 *
 * <p>채널은 별도 엔티티가 아니다({@link ChannelQueryService} 주석). 구독료를 정하면 비로소 그 회원의
 * 채널이 "열린다" — 정하기 전에는 {@code SubscriptionService} 가 {@code FEE_NOT_SET} 으로 구독을
 * 막으므로, 이 서비스가 없으면 유료 구독을 시작할 수 있는 사람이 아무도 없다.
 *
 * <p><b>{@code publisher_fees} 는 쌓는 표다.</b> 값을 바꾸는 것이 아니라 행을 더한다. 그래서 이력이
 * 그대로 남고, 구독자가 가격 변동을 볼 수 있다 — 이력 자체가 잦은 인상을 막는 억제 장치다.
 */
@Service
@RequiredArgsConstructor
public class ChannelFeeService {

    /**
     * 구독료 상한. 서버가 지켜야 할 도메인 규칙이라기보다 <b>오타 방어</b>다 — 0 을 하나 더 붙인
     * 값이 저장되면 구독자 전원에게 인상 알림이 나가고 이력에 남아 되돌릴 수 없다. 프론트
     * {@code api/channelFee.ts} 의 {@code FEE_MAX} 와 같은 값이다. 명세·ERD 에는 상한이 없다.
     */
    public static final BigInteger FEE_MAX = BigInteger.valueOf(100_000);

    /** {@code notifications.type} 은 24자 제한이다. */
    private static final String NOTIFICATION_TYPE = "FEE_CHANGED";

    private final UserRepository users;
    private final PublisherFeeRepository fees;
    private final SubscriptionRepository subscriptions;
    private final NotificationRepository notifications;
    private final NotificationGate notificationGate;

    /**
     * 현재값 + 변경 이력.
     *
     * <p>현재값을 "이력의 첫 줄" 로 계산하지 않고 {@link PublisherFeeRepository#findCurrent} 로 따로
     * 읽는다. 이력은 미래 발효분까지 담으므로 첫 줄이 아직 시작하지 않은 값일 수 있다 — 그 값을
     * 현재가라고 내리면 화면이 "지금 구독료" 자리에 아직 오지 않은 가격을 그린다.
     */
    @Transactional(readOnly = true)
    public ChannelFeeResponse fee(Long publisherId) {
        String current =
                fees.findCurrent(publisherId, Instant.now(), Limit.of(1))
                        .map(row -> row.getFee().toString())
                        .orElse(null);
        List<FeeChange> history =
                fees.findByPublisher_IdOrderByEffectiveFromDescIdDesc(publisherId).stream()
                        .map(FeeChange::of)
                        .toList();
        return new ChannelFeeResponse(current, history);
    }

    /**
     * 구독료 변경. 새 행 하나와 구독자 알림이 결과다.
     *
     * <p><b>즉시 발효한다</b>({@code effectiveFrom = now}). 그래도 "차기 결제 주기부터" 라는 약속은
     * 지켜진다 — 이미 걸린 구독은 결제 시점 가격을 {@code subscriptions.fee} 에 박제하고 있고
     * ({@code Subscription.request}), 갱신 배치 B4 는 그때 다시 현재가를 읽는다. 지금 바뀌는 것은
     * <b>앞으로 새로 걸릴 구독</b>의 값뿐이다. 예약 발효를 두면 "지금 구독하면 얼마인가" 를 화면과
     * 서버가 다르게 말하는 창이 생긴다.
     */
    @Transactional
    public ChannelFeeAppliedResponse update(Long publisherId, String feeText) {
        BigInteger fee = new BigInteger(feeText);
        if (fee.signum() < 0 || fee.compareTo(FEE_MAX) > 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "fee");
        }

        User publisher =
                users.findById(publisherId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));

        Instant now = Instant.now();
        fees.save(PublisherFee.of(publisher, fee, now));
        notifySubscribers(publisher, fee, now);

        return new ChannelFeeAppliedResponse(fee.toString(), now);
    }

    /** 가격이 바뀌었음을 알린다. 구독자가 없으면 아무것도 하지 않는다. */
    private void notifySubscribers(User publisher, BigInteger fee, Instant now) {
        List<Long> targets = notificationGate.filter(
                subscriptions.findFeeNoticeTargets(
                        publisher.getId(),
                        List.of(Subscription.Status.PENDING, Subscription.Status.ACTIVE),
                        now),
                        NOTIFICATION_TYPE);
        if (targets.isEmpty()) {
            return;
        }

        // 닉네임은 nullable 이다(온보딩 미완료). 그대로 이어붙이면 "null 채널의…" 로 나간다.
        // 대신할 말이 "구독 중인 채널" 이라 뒤에 "채널의" 를 또 붙이면 "채널 채널의" 가 됐다
        // (2026-09-22). 채널을 가리키는 말까지 통째로 고른다.
        String nickname = publisher.getNickname();
        String who = KoreanText.honorific(nickname, null);
        String body =
                (who == null ? "구독 중인 채널의" : who + "의 채널")
                        + " 구독료가 "
                        + fee
                        + " ANT 로 바뀝니다. 지금 구독 중인 기간은 결제한 가격 그대로입니다.";
        String linkPath = "/channels/" + publisher.getId();
        notifications.saveAll(
                targets.stream()
                        // getReferenceById 는 프록시라 SELECT 를 내지 않는다. 알림 행에 필요한 것은
                        // FK 값뿐이므로 구독자 수만큼 User 를 적재할 이유가 없다(ReportService 와 같다).
                        .map(
                                subscriberId ->
                                        Notification.create(
                                                users.getReferenceById(subscriberId),
                                                NOTIFICATION_TYPE,
                                                "구독료가 변경되었습니다",
                                                body,
                                                linkPath))
                        .toList());
    }
}
