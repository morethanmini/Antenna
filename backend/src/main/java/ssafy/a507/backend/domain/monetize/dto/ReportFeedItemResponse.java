package ssafy.a507.backend.domain.monetize.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ssafy.a507.backend.domain.community.dto.AuthorResponse;
import ssafy.a507.backend.domain.monetize.entity.Report;

/**
 * GET /api/v1/reports 목록 한 줄. 제목·작성자·통계는 항상 공개이고 본문은 실려 있지 않다 —
 * 화면은 {@code locked} 로 잠금 배지만 그린다.
 *
 * <p>{@code likeCount} · {@code commentCount} 는 <b>잠긴 리포트에도 실린다</b>. 통계가 늘 공개라는
 * 이 화면의 규칙 그대로다 — 반응이 많다는 사실 자체가 구독 유인이다. 누를 수 있는지는 다른
 * 문제이고, 그쪽은 403 {@code REPORT_FORBIDDEN} 으로 막는다(ANT-REPORT-SOCIAL).
 *
 * <p>{@code thumbnailUrl} · {@code summary} 는 <b>잠긴 리포트에 실리지 않는다.</b> 통계와 규칙이
 * 다른 이유는 이 둘이 본문에서 나온 값이기 때문이다 — "잠긴 리포트는 본문을 한 글자도 내리지
 * 않는다"(ANT-REPORT-LOCK, 2026-09-17 미리보기 폐지)가 그대로 적용된다. 발췌 120자를 내리는
 * 것은 폐지한 미리보기를 목록에서 되살리는 일이고, 첫 이미지도 본문의 일부다. 잠긴 카드는
 * 화면이 기본 표지(블록체인 큐브) + 자물쇠 배지로 그린다.
 *
 * <p>{@code NON_NULL} — 둘 다 없을 수 있는 값이라 키를 빼서 내린다. 프론트는 "없음" 하나만
 * 다루면 되고, 잠긴 글과 이미지 없는 글을 목록에서 구분할 이유가 없다(둘 다 기본 표지다).
 *
 * <p>명세의 {@code sector} 는 빠져 있다. 출처가 될 컬럼이 ERD 에 없어(Jira S15P21A507-69 의
 * ⛔ 항목) 팀 결정 전까지 구현하지 않는다. null 로라도 내리지 않는 이유는, 프론트가 "섹터가
 * 없는 리포트"와 "아직 구현되지 않은 필드"를 구분할 수 없기 때문이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportFeedItemResponse(
        Long id,
        String title,
        AuthorResponse author,
        boolean locked,
        Instant publishedAt,
        int viewCount,
        long likeCount,
        boolean liked,
        long commentCount,
        String thumbnailUrl,
        String summary) {

    /**
     * 본문 첫 이미지. 마크다운 {@code ![alt](url "title")} 에서 url 만 뽑는다.
     *
     * <p>본문은 react-markdown 이 그리므로 여기 없는 문법(원시 HTML {@code <img>})은 화면에도
     * 그림으로 나오지 않는다 — 렌더러가 rehype-raw 를 달지 않아 글자로 나온다(Markdown.tsx).
     * 그래서 마크다운 이미지 하나만 찾으면 충분하다.
     */
    private static final Pattern FIRST_IMAGE =
            Pattern.compile("!\\[[^\\]]*]\\(\\s*(\\S+?)\\s*(?:\"[^\"]*\")?\\s*\\)");

    /** 링크 {@code [글자](주소)} 는 글자만 남긴다 — 발췌에 주소가 끼면 읽을 수 없다 */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)]\\(\\s*\\S+?\\s*(?:\"[^\"]*\")?\\s*\\)");

    /** 줄머리 기호(제목 #, 인용 >, 목록 -·*·1.)와 강조 기호(*_`~) */
    private static final Pattern LINE_MARK = Pattern.compile("(?m)^\\s*(?:#{1,6}|>|[-*+]|\\d+\\.)\\s+");

    private static final Pattern EMPHASIS = Pattern.compile("[*_`~]+");

    /** 카드 발췌 두 줄. 화면이 두 줄에서 자르므로 그보다 넉넉하면 된다 */
    private static final int SUMMARY_MAX = 120;

    public static ReportFeedItemResponse of(
            Report report,
            boolean locked,
            long likeCount,
            boolean liked,
            long commentCount,
            String avatarUrl) {
        String body = locked ? null : report.getBody();
        return new ReportFeedItemResponse(
                report.getId(),
                report.getTitle(),
                AuthorResponse.of(report.getUser(), avatarUrl),
                locked,
                report.getCreatedAt(),
                report.getViewCount(),
                likeCount,
                liked,
                commentCount,
                firstImage(body),
                summarize(body));
    }

    /* 테스트가 부를 수 있게 package-private 이다 — 마크다운 파싱은 of() 를 거치지 않고 직접 확인한다 */
    static String firstImage(String body) {
        if (body == null) {
            return null;
        }
        Matcher m = FIRST_IMAGE.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 본문 앞부분을 카드에 얹을 평문으로 만든다.
     *
     * <p>이미지를 <b>먼저</b> 지운다 — 링크 규칙이 {@code ![alt](url)} 의 {@code [alt](url)}
     * 부분을 먼저 집으면 발췌가 느낌표로 시작한다.
     */
    static String summarize(String body) {
        if (body == null) {
            return null;
        }
        String text = FIRST_IMAGE.matcher(body).replaceAll(" ");
        text = LINK.matcher(text).replaceAll("$1");
        text = LINE_MARK.matcher(text).replaceAll("");
        text = EMPHASIS.matcher(text).replaceAll("");
        text = text.replaceAll("\\s+", " ").trim();

        if (text.isEmpty()) {
            return null;
        }
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX).trim() + "…";
    }
}
