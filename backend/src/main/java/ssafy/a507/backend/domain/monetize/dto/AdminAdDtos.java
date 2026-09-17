package ssafy.a507.backend.domain.monetize.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.chain.entity.Operation;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;

/** 관리자 광고 승인 (ANT-ADMIN-03) 요청·응답. 작은 record 가 여럿이라 한 파일에 묶는다. */
public final class AdminAdDtos {

    private AdminAdDtos() {}

    /**
     * 목록 한 줄.
     *
     * <p>{@code advertiserAvailable} 은 신청자의 <b>지금 가용잔액</b>(정수 ANT 문자열). 심사 대기 건에만 채운다 —
     * 운영자가 승인을 누르기 전에 "돈이 있나" 를 보게 하려는 것이다. 체인을 못 읽으면 {@code null}.
     *
     * <p>{@code burnPending} 은 승인은 눌렀는데 소각 tx 확정을 기다리는 중이라는 뜻이다. 배너 상태만 보면
     * 심사 대기와 구분이 안 된다(둘 다 PENDING).
     */
    public record AdminAdItem(
            Long id,
            Long advertiserId,
            String advertiserNickname,
            String imageUrl,
            String linkUrl,
            long days,
            Instant startsAt,
            Instant endsAt,
            String status,
            String price,
            String txHash,
            Instant createdAt,
            String advertiserAvailable,
            boolean burnPending) {

        public static AdminAdItem of(AdBanner b, String advertiserAvailable, boolean burnPending) {
            return new AdminAdItem(
                    b.getId(),
                    b.getAdvertiser().getId(),
                    b.getAdvertiser().getNickname(),
                    b.getImageUrl(),
                    b.getLinkUrl(),
                    b.days(),
                    b.getStartsAt(),
                    b.getEndsAt(),
                    b.getStatus().name(),
                    b.getPriceWei().toString(),
                    b.getTxHash(),
                    b.getCreatedAt(),
                    advertiserAvailable,
                    burnPending);
        }
    }

    public record AdminAdListResponse(List<AdminAdItem> items) {}

    /** 승인이면 그 자리에서 게재료 소각 tx 가 나간다. 반려면 아무것도 태우지 않는다. */
    public record AdHandleRequest(@NotNull(message = "처리 방식을 선택해 주세요.") Action action) {

        public enum Action {
            APPROVE,
            REJECT
        }
    }

    /** 승인 202 응답. 확정(ACTIVE)은 인덱서 몫이라 작업 id 로 폴링한다 — 사용자 결제 응답과 같은 꼴이다. */
    public record AdApproveResponse(String operationId, Long adId, Operation.Status status) {}
}
