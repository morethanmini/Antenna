package ssafy.a507.backend.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 비속어 검사기 — 정규화가 흡수해야 할 우회 4종과, 흡수하면 안 되는 정상 문장을 고정한다.
 * 욕설 원문을 소스에 남기지 않으려고 사전과 같은 base64 로 적는다.
 *
 * <p>스프링 없이 돈다 — 사전 로더가 클래스패스 리소스만 읽으므로 컨텍스트가 필요 없다.
 */
@DisplayName("비속어 검사기")
class ProfanityFilterTest {

    private static ProfanityFilter filter;

    @BeforeAll
    static void setUp() {
        filter = new ProfanityFilter(new ProfanityWordlist());
    }

    private static String d(String b64) {
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("빈 값은 통과한다 — 필수 여부는 @NotBlank 몫")
    void 빈_값_통과() {
        assertThat(filter.contains(null)).isFalse();
        assertThat(filter.contains("")).isFalse();
    }

    /** 이 서비스에서 실제로 쓰일 문장들. 부분 문자열 매칭이 잡으면 안 되는 표본 — 사전을 넓힐 때 여기서 걸리면 그 단어는 못 넣는다. */
    @ParameterizedTest
    @ValueSource(strings = {
            "삼성전자 3분기 실적 발표 후 상승 예측",
            "개발자 커뮤니티에서 본 리포트",
            "시장이 발달하면서 개인 투자자가 늘었다",
            "병원 관련주 강세",
            "신규 상장 종목 분석",
            "애플 실적 기대감",
            "이 종목은 저평가라고 봅니다",
            "반도체 업황 바닥 확인",
            "한국은행 금리 동결",
            "예측가",
    })
    @DisplayName("정상 문장은 통과한다 — 오탐 표본")
    void 정상_문장_통과(String text) {
        assertThat(filter.contains(text)).as("오탐: %s", text).isFalse();
    }

    @Test
    @DisplayName("사전 항목이 포함되면 잡는다")
    void 사전_항목_검출() {
        assertThat(filter.contains(d("6rCc7IOI64G8") + " 방")).isTrue(); // 한국어 3음절 항목
        assertThat(filter.contains(d("7IOI64G8") + " 모임")).isTrue(); // 단독 2글자 항목
        assertThat(filter.contains(d("67OR7Iug") + "들 모여라")).isTrue(); // 한국어 항목
        assertThat(filter.contains("this is " + d("c2hpdA=="))).isTrue(); // 영어 항목
    }

    @Test
    @DisplayName("삽입 문자(공백·구분기호·자모·숫자) 우회를 잡는다")
    void 삽입_우회_검출() {
        String k0 = d("6rCc7IOI"); // 사전 항목 앞 2음절
        String k1 = d("64G8"); // 사전 항목 마지막 음절
        assertThat(filter.contains(k0 + " " + k1)).isTrue(); // 사이 공백
        assertThat(filter.contains(k0 + "-" + k1)).isTrue(); // 사이 구분기호
        assertThat(filter.contains(k0 + "ㅡ" + k1)).isTrue(); // 사이 단독 자모(ㅡ) 삽입
        assertThat(filter.contains(k0 + "1" + k1)).isTrue(); // 사이 숫자 삽입
        assertThat(filter.contains(k0 + "９" + k1)).isTrue(); // 사이 전각 숫자 삽입
        assertThat(filter.contains(k0 + "​" + k1)).isTrue(); // 제로폭 공백 삽입
    }

    @Test
    @DisplayName("대소문자·과장 반복 우회를 잡는다")
    void 대소문자_반복_우회_검출() {
        String ew = d("c2hpdA=="); // 영어 사전 항목
        assertThat(filter.contains(ew.toUpperCase())).isTrue(); // 대문자
        assertThat(filter.contains(ew.charAt(0) + String.valueOf(ew.charAt(1)).repeat(3) + ew.substring(2)))
                .isTrue(); // 과장 반복
    }

    @Test
    @DisplayName("정규화 — 프론트 profanity.ts 와 같은 결과여야 한다 (크로스 픽스처)")
    void 정규화_프론트와_동일() {
        // 프론트 테스트가 같은 입력·출력을 갖는다. 한쪽을 고치면 양쪽을 같이 고친다.
        assertThat(ProfanityFilter.normalize("A B C")).isEqualTo("abc");
        assertThat(ProfanityFilter.normalize("a-b-c")).isEqualTo("abc");
        assertThat(ProfanityFilter.normalize("가ㅡ나")).isEqualTo("가나");
        assertThat(ProfanityFilter.normalize("가1나")).isEqualTo("가나");
        assertThat(ProfanityFilter.normalize("haaappy")).isEqualTo("happy"); // 3회 이상만 축약, 2회(pp)는 그대로
        assertThat(ProfanityFilter.normalize("SiBal")).isEqualTo("sibal");
        assertThat(ProfanityFilter.normalize("가​나")).isEqualTo("가나");
    }
}
