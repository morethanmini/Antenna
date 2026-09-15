package ssafy.a507.backend.domain.monetize.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;
import ssafy.a507.backend.domain.monetize.report.MarkdownPlain;

/**
 * GET /api/v1/reports/{id} 200 응답. 잠기지 않았으면 {@code body}(전문), 잠겼으면
 * {@code preview}(앞 3줄)가 실린다 — 둘 중 하나만 나가고 나머지 키는 생략된다.
 *
 * <p>키를 바꿔 내리는 이유는 전문과 미리보기를 같은 필드에 담으면 프론트가 잘린 본문을
 * 전문으로 착각해 그리기 때문이다. 명세도 잠금 응답을
 * {@code { preview: "앞 3줄", locked: true }} 로 적고 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportDetailResponse(
        Long id,
        String title,
        AuthorResponse author,
        boolean visibility,
        boolean locked,
        String body,
        String preview,
        Instant publishedAt,
        int viewCount,
        long likeCount,
        boolean liked,
        long commentCount) {

    /** 미리보기 줄 수. 명세가 "앞 3줄"로 못박은 값이다. */
    private static final int PREVIEW_LINES = 3;

    /**
     * 미리보기 글자 수 상한.
     *
     * <p>줄 수만으로 자르면 잠금이 무의미해진다 — 개행 없이 20000자를 쓴 본문은 1줄이라
     * "앞 3줄"이 전문과 같아진다. 명세에 없는 값이고 이 구현에서 정했다.
     */
    private static final int PREVIEW_MAX_CHARS = 300;

    /** 전문 공개. {@code viewCount} 는 이번 열람을 반영한 값을 호출부가 넘긴다. */
    public static ReportDetailResponse full(Report report, int viewCount, Social social) {
        return new ReportDetailResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.from(report.getUser()),
                report.isPublic(),
                false,
                report.getBody(),
                null,
                report.getCreatedAt(),
                viewCount,
                social.likeCount(),
                social.liked(),
                social.commentCount());
    }

    /** 잠금. 제목·작성자·통계는 그대로 공개하고 본문만 앞 3줄로 줄인다(구독 유인). */
    public static ReportDetailResponse locked(Report report, int viewCount, Social social) {
        return new ReportDetailResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.from(report.getUser()),
                report.isPublic(),
                true,
                null,
                preview(report.getBody()),
                report.getCreatedAt(),
                viewCount,
                social.likeCount(),
                social.liked(),
                social.commentCount());
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

    /**
     * 잠긴 리포트의 앞 3줄.
     *
     * <p>본문은 마크다운이라 원문을 그대로 자르면 {@code ## 제목} 이나 이미지 주소가 미리보기에
     * 뜬다. {@link MarkdownPlain} 으로 글자만 남긴 뒤 센다 — 그림 한 줄이나 문단 사이 빈 줄이
     * 세 줄 중 하나를 먹지 않도록 <b>글자가 있는 줄만</b> 줄로 센다.
     *
     * <p>벗기는 것은 보여 주기뿐이다. 저장도 봉인({@code bodyHash})도 원문 그대로다.
     */
    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        List<String> lines = MarkdownPlain.textLines(body);
        String head = String.join(
                "\n", lines.subList(0, Math.min(lines.size(), PREVIEW_LINES)));
        if (head.isEmpty()) {
            // 본문이 그림뿐이면 남는 글자가 없다. 빈 문자열을 주면 화면이 빈 칸을 그린다
            return null;
        }
        if (head.length() <= PREVIEW_MAX_CHARS) {
            return head;
        }
        return cut(head, PREVIEW_MAX_CHARS);
    }

    /**
     * 글자 수로 자르되 서로게이트 쌍을 쪼개지 않는다.
     *
     * <p>이모지는 UTF-16 에서 두 칸을 쓴다. 경계가 그 사이에 걸리면 앞쪽 절반만 남고, Jackson
     * 은 짝 없는 이스케이프를 내보낸다 — 화면에는 깨진 글자가, 엄격한 JSON 파서에는 오류가 된다.
     * 한 칸 물러서면 이모지 하나가 빠지는 것으로 끝난다.
     */
    static String cut(String value, int max) {
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
}
