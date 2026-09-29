package ssafy.a507.backend.domain.monetize.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.UserDtos.ChannelResponse;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.entity.UserInterest;
import ssafy.a507.backend.domain.account.repository.UserChannelRepository;
import ssafy.a507.backend.domain.account.repository.UserInterestRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.account.service.AvatarUrls;
import ssafy.a507.backend.domain.monetize.dto.ChannelProfileResponse;
import ssafy.a507.backend.domain.monetize.dto.ChannelProfileResponse.ChannelStats;
import ssafy.a507.backend.domain.monetize.dto.ChannelProfileResponse.MySubscription;
import ssafy.a507.backend.domain.monetize.entity.Subscription;
import ssafy.a507.backend.domain.monetize.repository.PublisherFeeRepository;
import ssafy.a507.backend.domain.common.Track;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;
import ssafy.a507.backend.domain.prediction.dto.PortfolioResponse;
import ssafy.a507.backend.domain.prediction.service.PortfolioQueryService;

/**
 * 채널 프로필 조회 — ANT-TOKEN-09, 화면 E-02.
 *
 * <p>채널은 별도 엔티티가 아니다. ERD 에 {@code channels} 표가 없고 {@code publisher_fees.publisher_id}
 * 가 {@code users.id} 를 직접 가리킨다 — 회원이 곧 채널이고, 구독료를 정하면 "열린" 채널이 된다.
 * 그래서 이 서비스는 회원·예측·구독·구독료 네 곳에서 모아 한 응답으로 낸다.
 *
 * <p>실적 통계는 {@link PortfolioQueryService#stats(Long, Track)} 을 {@code REAL} 로 부른다. 적중률 계산을
 * 여기 따로 두면 같은 사람의 적중률이 C-04 화면과 E-02 화면에서 서로 다르게 나온다.
 */
@Service
@RequiredArgsConstructor
public class ChannelQueryService {

    private final UserRepository users;
    private final UserInterestRepository interests;
    private final UserChannelRepository externalChannels;
    private final AvatarUrls avatarUrls;
    private final PortfolioQueryService portfolios;
    private final PublisherFeeRepository fees;
    private final SubscriptionRepository subscriptions;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public ChannelProfileResponse profile(Long userId) {
        User user =
                users.findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND, "userId"));

        Long viewerId = currentUserProvider.currentUserId();
        /* AC: 실적은 REAL 트랙만이다. predictions 표가 REAL 과 REPLAY 를 함께 담으므로
           트랙을 안 걸면 모의투자 성적이 채널 적중률에 섞인다 — 오류 없이 숫자만 틀린다. */
        PortfolioResponse portfolio = portfolios.stats(userId, Track.REAL);

        return new ChannelProfileResponse(
                user.getId(),
                user.getNickname(),
                avatarUrls.of(user),
                avatarUrls.bannerOf(user),
                user.getIntroduce(),
                interests.findByUserIdOrderByIdAsc(userId).stream()
                        .map(UserInterest::getSector)
                        .toList(),
                externalChannels.findByUserIdOrderByIdAsc(userId).stream()
                        .map(ChannelResponse::from)
                        .toList(),
                currentFee(userId),
                new ChannelStats(
                        portfolio.hitRate(),
                        portfolio.hit() + portfolio.miss(),
                        portfolio.avgError()),
                subscriptions.countSubscribers(userId, Subscription.Status.ACTIVE, Instant.now()),
                mySubscription(viewerId, userId),
                userId.equals(viewerId));
    }

    /** 정수 ANT 문자열. 아직 안 정했으면 null 이다 — 0 으로 내리면 무료 채널로 읽힌다 */
    private String currentFee(Long publisherId) {
        return fees.findCurrent(publisherId, Instant.now(), Limit.of(1))
                .map(fee -> fee.getFee().toString())
                .orElse(null);
    }

    /** 내 채널을 내가 볼 때는 구독 개념이 없다 — 자기 자신을 구독할 수 없어 늘 빈 상태다 */
    private MySubscription mySubscription(Long viewerId, Long publisherId) {
        if (viewerId == null || viewerId.equals(publisherId)) {
            return MySubscription.NONE;
        }
        return subscriptions
                .findLatestBetween(viewerId, publisherId, Limit.of(1))
                .map(MySubscription::of)
                .orElse(MySubscription.NONE);
    }
}
