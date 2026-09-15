package ssafy.a507.backend.domain.monetize.report;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 마크다운에서 글자만 남긴다. <b>미구독자 미리보기(앞 3줄) 전용</b>이다.
 *
 * <p>리포트 본문은 마크다운이다(ANT-FE-REPORT-MD). 잠긴 리포트의 앞 3줄을 원문 그대로 내보내면
 * 구독을 권하는 자리에 {@code ## 반도체 업황} 이나 {@code ![](/api/v1/uploads/a1b2…)} 같은 문법이
 * 뜬다 — 읽히지도 않고, 그림 한 줄이 미리보기 세 줄 중 하나를 통째로 먹는다.
 *
 * <p><b>여기서 하는 일은 보여 주기뿐이다.</b> 저장·봉인은 원문 그대로다 —
 * {@link ReportSeal} 의 {@code bodyHash} 는 손대지 않은 {@code body} 를 해시한다. 이 클래스가
 * 만드는 문자열은 어떤 해시에도 들어가지 않는다. 들어가면 규칙을 고칠 때마다 옛 리포트의
 * 검증이 깨진다.
 *
 * <p><b>완전한 마크다운 파서가 아니다.</b> 세 줄을 읽을 만하게 만드는 것이 목적이라 흔한 문법만
 * 걷어내고 나머지는 글자로 남긴다. 파서를 들이지 않는 이유는 서버가 마크다운을 해석할 일이 이
 * 한 곳뿐이고, 여기서 틀려도 손해가 "미리보기가 조금 덜 예쁘다" 뿐이기 때문이다.
 */
public final class MarkdownPlain {

    private MarkdownPlain() {}

    /** 이미지. 통째로 지운다 — 미리보기는 글이고, alt 는 비어 있는 경우가 대부분이다. */
    private static final Pattern IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\([^)]*\\)");

    /** 링크는 글자만 남긴다. 주소는 미리보기에서 읽을 값이 아니다. */
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");

    /** 줄머리 문법 — 제목 · 인용 · 목록(글머리표와 번호). */
    private static final Pattern LINE_PREFIX =
            Pattern.compile("^\\s{0,3}(#{1,6}\\s+|>\\s?|[-*+]\\s+|\\d+[.)]\\s+)");

    /** 코드 울타리와 가로줄. 줄 전체가 이것뿐이면 글이 없는 줄이다. */
    private static final Pattern FENCE_OR_RULE =
            Pattern.compile("^\\s*(```+|~~~+|([-*_])\\s*(\\2\\s*){2,})\\s*$");

    /** 표 구분선(|---|:--:|). 글자가 없다. */
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|[\\s:|-]*$");

    /** 강조 · 취소선 · 인라인 코드. 감싸는 기호만 벗기고 안의 글자는 그대로 둔다. */
    private static final Pattern EMPHASIS = Pattern.compile("(\\*\\*|__|~~|[*_`])");

    /**
     * 글자만 남은 줄들을 순서대로 돌려준다. 빈 줄은 빠진다 — 문단 사이 빈 줄이
     * 미리보기 세 줄 중 하나를 먹으면 남는 것은 두 줄뿐이다.
     *
     * @param body 리포트 본문 원문(마크다운). {@code null} 이면 빈 목록.
     */
    public static List<String> textLines(String body) {
        List<String> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        // 개행은 \r?\n 으로 본다 — \n 만 보면 CRLF 본문의 각 줄 끝에 \r 이 남는다.
        for (String raw : body.split("\r?\n")) {
            if (FENCE_OR_RULE.matcher(raw).matches() || TABLE_RULE.matcher(raw).matches()) {
                continue;
            }
            String line = strip(raw);
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    /** 한 줄에서 문법을 걷어낸다. 줄머리는 중첩 목록·인용을 감안해 더 걷을 것이 없을 때까지 본다. */
    private static String strip(String raw) {
        String line = IMAGE.matcher(raw).replaceAll("");
        line = LINK.matcher(line).replaceAll("$1");

        // "> - 항목" 처럼 겹친 줄머리는 한 번에 못 걷는다. 줄지 않으면 멈춘다(무한 루프 방지)
        while (true) {
            String next = LINE_PREFIX.matcher(line).replaceFirst("");
            if (next.equals(line)) {
                break;
            }
            line = next;
        }

        line = EMPHASIS.matcher(line).replaceAll("");
        return line.strip();
    }
}
