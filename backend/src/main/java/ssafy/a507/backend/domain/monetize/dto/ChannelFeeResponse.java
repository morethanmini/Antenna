package ssafy.a507.backend.domain.monetize.dto;

import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.monetize.entity.PublisherFee;

/**
 * GET /api/v1/me/channel/fee 200 응답 (ANT-TOKEN-05, 화면 E-04).
 *
 * <p>필드 이름은 프론트 {@code api/channelFee.ts} 의 {@code ChannelFee} 타입에 맞췄다. 명세서가
 * 경로와 요청 본문까지만 정하고 응답 형태를 비워 둬서, 화면이 ERD {@code publisher_fees} 컬럼에서
 * 따온 이름을 먼저 쓰고 있었다 — 서버가 다른 이름을 내리면 화면이 조용히 빈칸을 그린다.
 *
 * <p>금액은 정수 ANT 문자열이다(decimals 0). {@code wei} 가 아니다 — ANT-TOKEN-08 과 같다.
 */
public record ChannelFeeResponse(
        /**
         * 지금 적용 중인 구독료.
         *
         * <p><b>한 번도 정한 적이 없으면 null 이고 0 이 아니다.</b> 0 으로 내리면 화면이 무료 채널로
         * 읽는다. 아직 시작하지 않은 예약 변경만 있는 경우에도 null 이다 — "현재"는 발효일이 지난
         * 행에서만 온다.
         */
        String fee,
        /** 최신순. 미래 발효분도 담는다(화면이 "예정" 으로 구분해 그린다). 처음이면 빈 배열 */
        List<FeeChange> history) {

    /** {@code publisher_fees} 한 행 */
    public record FeeChange(Long id, String fee, Instant effectiveFrom, Instant createdAt) {

        public static FeeChange of(PublisherFee row) {
            return new FeeChange(
                    row.getId(), row.getFee().toString(), row.getEffectiveFrom(), row.getCreatedAt());
        }
    }
}
