package ssafy.a507.backend.common.text;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 비속어 검사 (ANT-COMMUNITY-09) — 프론트 {@code src/text/profanity.ts} 와 같은 정규화를 쓴다.
 *
 * <p>정규화 순서(소문자 → NFC → 공백/제로폭 제거 → 구분기호 제거 → 단독 자모 제거 → 숫자 제거
 * → 3회 이상 반복 축약)를 프론트와 동일하게 유지해야 화면 선검사와 서버 판정이 일치한다.
 * 화면이 통과시킨 글을 서버가 거절하면 사용자는 "왜 안 되는지" 알 길이 없다.
 *
 * <p>마스킹은 두지 않는다 — 이 서비스의 글은 대부분 서명·온체인 봉인 재료라(리포트 제목·본문, 예측 근거)
 * 서버가 글자를 바꾸면 검산이 깨진다. 걸리면 거절뿐이다.
 *
 * <p>S15P11A706 {@code ProfanityFilter} 에서 {@code contains} 만 이식했다.
 */
@Component
public class ProfanityFilter {

    /** 정규화된 사전. */
    private final List<String> normalizedWords;

    public ProfanityFilter(ProfanityWordlist wordlist) {
        // 정규화 결과가 1글자로 "줄어든" 항목(예: 숫자 우회형 "씨8" → "씨")은 부분 매칭에서
        // 정상 문장을 마구 잡으므로 안전망으로 걸러낸다(사전 큐레이션 실수 방어).
        // 단, 원문부터 1글자인 항목은 큐레이터가 의도한 것이므로 그대로 둔다.
        this.normalizedWords = wordlist.decodedWords().stream()
                .filter(w -> w.length() == 1 || normalize(w).length() >= 2)
                .map(ProfanityFilter::normalize)
                .filter(w -> !w.isEmpty())
                .distinct()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }

    /** 텍스트에 사전 속 비속어가 포함되면 true. null·빈 값은 false — 필수 여부는 {@code @NotBlank} 몫이다. */
    public boolean contains(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String normalized = normalize(text);
        for (String word : normalizedWords) {
            if (normalized.contains(word)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 비교용 정규화 — 대소문자·공백·구분기호·삽입 자모·삽입 숫자·과장 반복으로 하는 단순 우회를 흡수한다.
     * 예) {@code "시 발"} · {@code "시.발"} · {@code "시ㅡ발"} · {@code "시1발"} · {@code "SiBal"} · {@code "시이이발"}.
     * 소문자화는 길이가 변하지 않도록 문자 단위 {@link Character#toLowerCase(char)} 를 쓴다.
     */
    static String normalize(String raw) {
        String nfc = Normalizer.normalize(raw, Normalizer.Form.NFC);

        // 1단계: 제거 대상(공백·구분기호·자모·숫자)을 걸러낸다.
        StringBuilder kept = new StringBuilder(nfc.length());
        for (int i = 0; i < nfc.length(); i++) {
            char c = Character.toLowerCase(nfc.charAt(i));
            if (!isRemovable(c)) {
                kept.append(c);
            }
        }

        // 2단계: 같은 문자 3회 이상 반복을 1회로 축약한다 ("haaappy" → "happy" — 2회 반복은 그대로 둔다).
        StringBuilder out = new StringBuilder(kept.length());
        int i = 0;
        while (i < kept.length()) {
            int j = i;
            while (j + 1 < kept.length() && kept.charAt(j + 1) == kept.charAt(i)) {
                j++;
            }
            int run = j - i + 1;
            if (run >= 3) {
                out.append(kept.charAt(i));
            } else {
                out.append(kept, i, j + 1);
            }
            i = j + 1;
        }
        return out.toString();
    }

    /**
     * 프론트 RE_SPACE·RE_SEP·RE_JAMO·RE_DIGIT 문자 클래스의 합집합.
     * 안 보이는 문자가 소스에 섞이지 않도록 비ASCII 는 전부 16진 코드포인트로 비교한다.
     */
    private static boolean isRemovable(char c) {
        // 공백 + 제로폭 — JS \s 에 포함되는 NBSP 류(00A0, 202F)를 명시적으로 더한다
        if (Character.isWhitespace(c) || c == 0x00A0 || c == 0x202F
                || (c >= 0x200B && c <= 0x200D) || c == 0xFEFF) {
            return true;
        }
        // 구분기호·점·각종 대시(2010-2015)·가운뎃점류(00B7, 2022, 2027, 30FB)·장음(30FC)·역슬래시·하이픈
        if (c == '.' || c == '_' || c == '*' || c == '~' || c == '|' || c == '/' || c == '\\' || c == '-'
                || (c >= 0x2010 && c <= 0x2015)
                || c == 0x00B7 || c == 0x2022 || c == 0x2027 || c == 0x30FB || c == 0x30FC) {
            return true;
        }
        // 음절 사이 낀 단독 한글 자모(우회용) — 첫가끝 자모·호환 자모·확장 자모
        if ((c >= 0x1100 && c <= 0x11FF) || (c >= 0x3130 && c <= 0x318F)
                || (c >= 0xA960 && c <= 0xA97F) || (c >= 0xD7B0 && c <= 0xD7FF)) {
            return true;
        }
        // 음절 사이 낀 숫자(반각+전각, 우회용)
        return (c >= '0' && c <= '9') || (c >= 0xFF10 && c <= 0xFF19);
    }
}
