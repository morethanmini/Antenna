package ssafy.a507.backend.domain.monetize.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.account.dto.UserDtos.ChannelResponse;
import ssafy.a507.backend.domain.monetize.entity.Subscription;

/**
 * GET /api/v1/channels/{userId} 200 응답 (ANT-TOKEN-09, 화면 E-02).
 *
 * <p>필드 이름은 프론트 {@code api/channels.ts} 의 {@code Channel} 타입에 맞췄다. 명세 v0.62 까지는
 * {@code introduce} · {@code channels} · {@code fee "…wei"} 로 적혀 있었는데, 화면은 이미
 * {@code bio} · {@code externalLinks} · 정수 ANT 로 만들어져 있었다. 명세를 v0.63 으로 올려 맞춘다 —
 * 서버가 옛 이름을 내리면 화면이 조용히 빈칸을 그린다.
 *
 * <p>금액이 문자열인 이유는 ANT-TOKEN-04 와 같다 — 금액 필드는 전부 정수 ANT 문자열이다(decimals 0).
 * {@code wei} 가 아니다.
 */
public record ChannelProfileResponse(
        Long userId,
        String nickname,
        /** 사진이 없거나 파일이 지워졌으면 null. 그때는 화면이 기본 그림을 쓴다 */
        String avatarUrl,
        /** 소개. 안 적었으면 null */
        String bio,
        /** 관심 섹터. 없으면 빈 배열 — null 을 내리면 화면마다 가드를 따로 써야 한다 */
        List<String> interests,
        /** 외부 채널. 등록 순 */
        List<ChannelResponse> externalLinks,
        /**
         * 현재 구독료(정수 ANT 문자열).
         *
         * <p><b>안 정했으면 null 이고 0 이 아니다.</b> 채널은 가입과 동시에 존재하지만(ERD 에 channels
         * 테이블이 없고 publisher_fees.publisher_id 가 users.id 를 직접 가리킨다) 구독료는 정해야 생긴다.
         * 0 으로 내리면 무료 채널로 읽힌다.
         */
        String fee,
        ChannelStats stats,
        /** 지금 유효한(ACTIVE + 기간 남음) 구독자 수. 개인은 내리지 않는다 — 수만(ANT-TOKEN-10) */
        long subscriberCount,
        MySubscription mySubscription,
        /** 내 채널이면 화면이 구독 카드 대신 설정 안내를 그린다 */
        boolean isMe,
        /**
         * 내가 이 회원을 신고해 두고 아직 처리되지 않았나 (화면 M-06).
         *
         * <p>화면이 신고 메뉴를 "신고함" 으로 바꾸는 근거다. 브라우저에 기억시키지 않고 서버가 내리는 이유가
         * 둘이다: ① 기기를 바꾸면 표시가 사라진다 ② 관리자가 처리해 {@code PENDING} 이 풀리면 재신고가
         * 허용되는데, 브라우저에 남은 표시는 그 사실을 모른 채 계속 막는다.
         *
         * <p>그래서 판정 기준이 중복 신고를 막는 조건과 <b>같아야 한다</b> —
         * {@code AbuseReportService.rejectDuplicate} 가 보는 것과 같은 (신고자, 대상, PENDING) 이다.
         */
        boolean reportedByMe) {

    /**
     * 채널 실적. <b>셋 고정이다</b>(설계 제약).
     *
     * <p>{@code hitRate} · {@code avgError} 는 판정 표본이 없으면 null 이다 — 0 으로 그리면 "한 번도 못
     * 맞혔다"로 읽힌다. {@code doneCount} 만 표본 수 자체라 0 이 정상값이다.
     */
    public record ChannelStats(BigDecimal hitRate, long doneCount, BigDecimal avgError) {}

    /**
     * 이 채널에 대한 <b>내</b> 구독 상태. 채널의 속성이 아니다.
     *
     * <p>구독한 적이 없으면 {@code status} 가 null 이다(객체 자체는 늘 내려간다 — 화면이 키마다 가드를
     * 쓰지 않게). PENDING 은 결제가 체인에서 확정되기를 기다리는 중이라 아직 열람 권한이 아니다.
     */
    public record MySubscription(
            Subscription.Status status,
            Instant expiresAt,
            boolean autoRenew,
            /** 결제 시점에 박제된 가격(정수 ANT 문자열). 채널이 가격을 바꿔도 이 값은 그대로다 */
            String paidFee) {

        public static final MySubscription NONE = new MySubscription(null, null, false, null);

        public static MySubscription of(Subscription s) {
            return new MySubscription(
                    s.getStatus(),
                    s.getExpiresAt(),
                    s.isAutoRenew(),
                    s.getFee() == null ? null : s.getFee().toString());
        }
    }
}
