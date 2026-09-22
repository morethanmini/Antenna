package ssafy.a507.backend.common.text;

/**
 * 알림 문구를 만들 때 쓰는 한국어 규칙 몇 가지.
 *
 * <p>알림 본문은 닉네임 · 리포트 제목처럼 <b>사용자가 정한 말</b>을 이어 붙여 만든다. 그래서
 * 조사를 글자로 박아 두면 절반은 틀린다 — "공급 과점 구조" 뒤에 "을" 을 붙이던 자리가
 * 그랬다. 받침을 보고 고르는 일을 한곳에 모은다.
 *
 * <p>원래 {@code AdminModerationService} 안에 private 으로 있던 것을 올렸다. 신고 문구만
 * 쓰던 규칙이 아니었다.
 */
public final class KoreanText {

    private KoreanText() {}

    /**
     * 받침에 맞는 조사 하나를 고른다. 한글이 아니거나 빈 말이면 받침 없는 쪽 — 숫자 · 영문으로
     * 끝나는 종목명이 실제로 들어온다("SK C&C").
     */
    public static String particle(String word, String withBatchim, String without) {
        if (word == null || word.isEmpty()) {
            return without;
        }
        char last = word.charAt(word.length() - 1);
        boolean hangul = last >= 0xAC00 && last <= 0xD7A3;
        boolean batchim = hangul && (last - 0xAC00) % 28 != 0;
        return batchim ? withBatchim : without;
    }

    /** 말 뒤에 받침에 맞는 조사를 붙인다 — "리포트는 · 글은". */
    public static String josa(String word, String withBatchim, String without) {
        return word + particle(word, withBatchim, without);
    }

    /**
     * 사람을 가리키는 말. 닉네임이 있으면 {@code "느린손 님"}, 없으면 대신할 말을 그대로 준다.
     *
     * <p>없을 때도 "님" 을 붙이면 {@code "누군가 님"} 처럼 어색해진다 — 사람 이름이 아니라
     * 자리를 메우는 말이기 때문이다. 닉네임은 온보딩을 마치지 않으면 null 이라 실제로 빈다.
     *
     * <p>이름과 "님" 은 띄어 쓴다. 이름 뒤의 "님" 은 의존 명사다("사장님" 의 접미사 -님과
     * 다르다).
     */
    public static String honorific(String nickname, String fallback) {
        return (nickname == null || nickname.isBlank()) ? fallback : nickname + " 님";
    }
}
