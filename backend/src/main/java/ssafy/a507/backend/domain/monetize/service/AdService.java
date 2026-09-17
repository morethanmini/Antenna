package ssafy.a507.backend.domain.monetize.service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.config.TokenAmountProperties;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.chain.repository.OperationRepository;
import ssafy.a507.backend.domain.chain.service.AvailableBalanceService;
import ssafy.a507.backend.domain.chain.service.TokenOperationService;
import ssafy.a507.backend.domain.monetize.dto.AdActiveResponse;
import ssafy.a507.backend.domain.monetize.dto.AdCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.AdCreateResponse;
import ssafy.a507.backend.domain.monetize.dto.AdPricingResponse;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;
import ssafy.a507.backend.domain.upload.entity.UploadFile;
import ssafy.a507.backend.domain.upload.repository.UploadFileRepository;

/**
 * 스폰서드 배너 (ANT-COMMUNITY-05). 고정 단가 × 기간제 선착순이고 지불은 소각이다.
 *
 * <p><b>승인제(ANT-ADMIN-03)</b> — 신청은 <b>접수만</b> 한다. 소각 tx 는 관리자가 승인하는 순간
 * {@link AdminAdService#approve} 가 보낸다. 반려·취소되면 아무것도 태우지 않는다. 그래서 이 클래스에는
 * 더 이상 "접수 커밋 → 트랜잭션 밖 전송" 의 수동 분리가 없다 — 그 구조는 승인 쪽으로 옮겨 갔다.
 *
 * <p>신청 시점에 돈이 안 나가는 대신 <b>가용잔액에서 묶인다</b>({@link AvailableBalanceService}) —
 * 심사 중인 7,000 ANT 로 배팅이나 구독을 할 수 없다. 사용자가 "광고 신청한 거 기억하고 남겨 둬야" 하는
 * UX 를 만들지 않으려는 것이고, 마음이 바뀌면 {@link #cancel} 로 스스로 푼다.
 */
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties({AdProperties.class, TokenAmountProperties.class})
public class AdService {

    /**
     * 최소 게재 기간. {@link AdProperties} 에 두지 않은 것은 하루 미만을 막는 곳이
     * {@link AdCreateRequest} 의 {@code @Min(1)} 이고, 애노테이션 인자는 컴파일 상수라
     * 설정으로 뺄 수 없기 때문이다. 그 1 과 같은 값을 조건 조회에서 내린다.
     */
    private static final int MIN_DAYS = 1;

    /** 슬롯을 차지하는 상태. 심사 대기(PENDING)도 자리를 잡는다 — 승인되는 순간 배너가 둘이 되면 안 된다. */
    static final List<AdBanner.Status> OCCUPYING = List.of(AdBanner.Status.ACTIVE, AdBanner.Status.PENDING);

    private final AdBannerRepository adBannerRepository;
    private final UploadFileRepository uploadFileRepository;
    private final UserRepository userRepository;
    private final OperationRepository operationRepository;
    private final SignatureGuard signatureGuard;
    private final AdProperties properties;
    /** 게재료 단가. 광고 조건(자리 수·기간)과 달리 금액은 토큰 금액표 한 곳에 둔다(ANT-TOKEN-08). */
    private final TokenAmountProperties amounts;
    private final TokenOperationService tokenOperationService;
    private final AvailableBalanceService availableBalance;

    /**
     * 게재 신청 — 접수하고 끝난다. 201. 돈은 관리자 승인 때 나간다.
     *
     * <p>검증 순서가 <b>기간 → 이미지 → 슬롯 → 가용잔액 → 서명</b> 인 것은 의도적이다.
     * {@link SignatureGuard#verify} 는 nonce 를 태운다. 서명을 먼저 검증하면 슬롯이 마감돼 409 로 돌려보내는
     * 요청에서도 nonce 가 사라져, 사용자는 지갑에서 다시 서명해야 한다. 앞의 것들은 상태를 바꾸지 않는 판정이다.
     * 거꾸로 서명 검증은 <b>행을 만들기 직전</b>이어야 한다 — 검증 없이 만들면 남의 지갑으로 결제되는 광고를
     * 등록할 수 있다.
     *
     * <p>{@code requireEnabled()} 가 맨 앞인 이유 — 가용잔액이 체인을 읽으므로 체인이 꺼져 있으면 어차피 503 인데,
     * 그 전에 아무것도 쓰지 않고 nonce 도 태우지 않는다는 것을 확실히 하려는 것이다.
     *
     * <p>지갑 없는 계정은 가용잔액에서 {@code WALLET_NOT_LINKED} 로 걸린다 — 예전엔 접수 뒤 전송에서야 실패했다.
     */
    @Transactional
    public AdCreateResponse create(Long userId, AdCreateRequest request) {
        tokenOperationService.requireEnabled();

        if (request.days() > properties.maxDays()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "days");
        }

        UploadFile image =
                uploadFileRepository
                        .findByIdAndUserId(request.imageFileId(), userId)
                        .orElseThrow(
                                () -> new BusinessException(ErrorCode.UPLOAD_FILE_NOT_FOUND, "imageFileId"));
        if (image.getPurpose() != UploadFile.Purpose.AD) {
            // 배너 비율 검증은 purpose=AD 로 올릴 때만 돈다. 다른 용도로 올린 파일을 여기서
            // 받아 주면 그 검증을 건너뛴 이미지가 배너 자리에 걸린다.
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "imageFileId");
        }

        // 게재 시작을 고를 수 없다 — 요청 본문에 기간(days)만 있고 시작일이 없다. 여기서 잡는 시작은 임시값이다.
        // 승인 시점에 다시 잡는다(AdBanner.approve) — 승인이 사흘 뒤면 그 사흘이 날아가면 안 된다.
        Instant startsAt = Instant.now();
        Instant endsAt = startsAt.plus(request.days(), ChronoUnit.DAYS);
        // ponytail: 세는 것과 만드는 것 사이가 열려 있어, 같은 순간에 들어온 두 신청이 둘 다
        // 슬롯을 통과할 수 있다. 제대로 막으려면 기간 겹침에 걸리는 배타 제약(Postgres 의
        // btree_gist + EXCLUDE)이나 슬롯 행 잠금이 필요하다. 자리가 하나뿐이고 신청이 하루
        // 몇 건인 단계라 넣지 않았다 — 실제로 부딪히면 EXCLUDE 제약이 가장 싸다.
        // excludeId 0 — 아직 행이 없다. 승인 때는 자기 id 를 넘겨 자신을 뺀다.
        if (adBannerRepository.countOccupying(OCCUPYING, startsAt, endsAt, 0L) >= properties.slotCount()) {
            throw new BusinessException(ErrorCode.AD_SLOT_SOLD_OUT);
        }

        BigInteger price = amounts.adFor(request.days());
        User advertiser =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        // 가용잔액 = 체인 잔액 − 전송 중인 내 돈(PENDING 배팅·구독·광고). 원장이 아니라 체인이다 — 인덱서 지연이 있다.
        if (availableBalance.of(advertiser).compareTo(price) < 0) {
            throw new BusinessException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        signatureGuard.verify(userId, request);

        AdBanner banner =
                adBannerRepository.save(
                        AdBanner.request(
                                advertiser,
                                image.getUrl(),
                                request.linkUrl(),
                                startsAt,
                                endsAt,
                                price));
        return new AdCreateResponse(banner.getId(), banner.getStatus());
    }

    /**
     * 신청 취소 — 본인의 심사 대기 건만. 배너를 닫으면 가용잔액 홀드가 저절로 풀린다(홀드가 곧 이 행이라서).
     *
     * <p>소각 tx 가 나가는 중이면 못 취소한다({@link #burnInFlight}) — 돈이 이미 체인으로 가고 있다.
     */
    @Transactional
    public void cancel(Long userId, Long adId) {
        AdBanner banner =
                adBannerRepository
                        .findWithAdvertiser(adId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.AD_NOT_FOUND));
        if (!banner.getAdvertiser().getId().equals(userId)) {
            throw new BusinessException(ErrorCode.AD_FORBIDDEN);
        }
        if (!banner.isPending() || burnInFlight(adId)) {
            throw new BusinessException(ErrorCode.AD_ALREADY_HANDLED);
        }
        banner.reject();
    }

    /**
     * 이 배너의 게재료 소각이 나가는 중인가 — 최근 작업이 PENDING 이면 그렇다.
     *
     * <p>배너에 "승인됨" 칸이 없어 작업으로 안다. 칸을 더하면 {@code ddl-auto: update} 로 팀 DB 에 번진다.
     * FAILED 면 다시 승인할 수 있고, 없으면 아직 안 눌렀고, SUCCEEDED 면 배너가 이미 ACTIVE 다.
     */
    boolean burnInFlight(Long adId) {
        return operationRepository
                .findFirstByResourceTypeAndResourceIdOrderByCreatedAtDesc(Operation.ResourceType.AD, adId)
                .map(op -> op.getStatus() == Operation.Status.PENDING)
                .orElse(false);
    }

    /**
     * 게재 조건. 설정값을 그대로 내리고 상태는 읽지 않는다 — 화면이 서명 전에 비용을 계산할
     * 근거이고, 이 값이 없으면 프론트가 단가를 복제해 두는 수밖에 없다.
     */
    public AdPricingResponse pricing() {
        return new AdPricingResponse(
                amounts.adPerDay().toString(),
                MIN_DAYS,
                properties.maxDays(),
                properties.slotCount());
    }

    /** 메인 노출용. 상태와 기간을 함께 보므로 만료 배치가 없어도 끝난 광고는 빠진다. */
    @Transactional(readOnly = true)
    public AdActiveResponse active() {
        Instant now = Instant.now();
        return AdActiveResponse.of(
                adBannerRepository
                        .findByStatusAndStartsAtLessThanEqualAndEndsAtAfterOrderByStartsAtAsc(
                                AdBanner.Status.ACTIVE, now, now));
    }
}
