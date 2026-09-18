package ssafy.a507.backend.domain.monetize.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;

/**
 * GET /api/v1/reports/{id} 200 응답. 잠기지 않았으면 {@code body}(전문)가 실리고, 잠겼으면
 * <b>본문은 한 글자도 나가지 않는다</b> — {@code body} 키가 생략되고 {@code locked:true} 만 간다.
 *
 * <p>전에는 잠긴 응답에 {@code preview}(앞 3줄·최대 300자)를 실었다. 상한이 절대값이라 세 줄이
 * 안 되는 짧은 글은 미리보기가 곧 전문이었다 — 구독자 전용으로 올린 글을 미구독자가 그대로
 * 읽었다(2026-09-17). 비율로 자르는 대신 미리보기를 없앴다. 구독 유인은 제목·작성자·통계가 맡는다.
 *
 * <p>{@code signerAddress} 는 발행 서명에서 복원한 지갑 주소다(ANT-CHAIN-16). <b>잠긴 리포트에도 실린다</b> —
 * 통계와 같은 부류이고, 누가 썼는지는 본문을 볼 수 있는지와 별개다. <b>서명 이전에 발행된 리포트는 이 키가
 * 아예 없다</b>({@code NON_NULL}) — null 로 내리면 프론트가 "서명이 비었다" 와 "서명 기능 이전 글" 을
 * 구분하지 못한다. 키가 없으면 후자다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportDetailResponse(
        Long id,
        String title,
        AuthorResponse author,
        boolean visibility,
        boolean locked,
        String body,
        Instant publishedAt,
        int viewCount,
        long likeCount,
        boolean liked,
        long commentCount,
        String signerAddress,
        /**
         * 신고로 가려진 리포트를 <b>관리자가</b> 보는 중이다. 평소에는 키가 없다(NON_NULL) — 가려진 리포트는
         * 관리자 말고는 410 이라 이 값이 true 로 내려가는 경우는 관리자뿐이다.
         */
        Boolean blocked) {

    /** 전문 공개. {@code viewCount} 는 이번 열람을 반영한 값을 호출부가 넘긴다. */
    public static ReportDetailResponse full(Report report, int viewCount, Social social) {
        return new ReportDetailResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.from(report.getUser()),
                report.isPublic(),
                false,
                report.getBody(),
                report.getCreatedAt(),
                viewCount,
                social.likeCount(),
                social.liked(),
                social.commentCount(),
                report.getSignerAddress(),
                null);
    }

    /** 관리자가 가려진 리포트를 볼 때. 나머지는 그대로 두고 표시만 붙인다 */
    public ReportDetailResponse asBlocked() {
        return new ReportDetailResponse(
                id, title, author, visibility, locked, body, publishedAt, viewCount,
                likeCount, liked, commentCount, signerAddress, true);
    }

    /** 잠금. 제목·작성자·통계는 그대로 공개하고 본문은 싣지 않는다. */
    public static ReportDetailResponse locked(Report report, int viewCount, Social social) {
        return new ReportDetailResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.from(report.getUser()),
                report.isPublic(),
                true,
                null,
                report.getCreatedAt(),
                viewCount,
                social.likeCount(),
                social.liked(),
                social.commentCount(),
                report.getSignerAddress(),
                null);
    }

    /**
     * 반응 통계 세 값. 인자로 흩뿌리지 않고 묶는 이유는 {@code full} 과 {@code locked} 가 받는
     * 인자가 {@code (Report, int, long, boolean, long)} 이 되면 호출부에서 순서를 틀려도
     * 컴파일이 통과하기 때문이다 — {@code viewCount} 와 {@code likeCount} 가 둘 다 숫자다.
     *
     * <p>세 값 모두 <b>잠긴 리포트에도 실린다</b>. 통계는 늘 공개라는 이 화면의 규칙 그대로다.
     */
    public record Social(long likeCount, boolean liked, long commentCount) {
    }
}
