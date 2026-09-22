package ssafy.a507.backend.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 닉네임 문자 규칙({@link NicknameFormat}) — 공백·특수문자·보이지 않는 문자·사칭 스크립트 차단.
 *
 * <p>정규식 하나의 동작을 고정한다. 실제 DTO 에 붙어 있는지는 {@code ProfileUpdateTest} 가 HTTP 로 확인한다.
 * {@link NoProfanity} 와 달리 스프링 빈이 필요 없어 기본 팩토리로 돈다.
 */
@DisplayName("닉네임 문자 규칙")
class NicknameFormatTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    /** 애너테이션을 실제 필드에 붙여서 검증한다 — 정규식 문자열만 시험하면 @Pattern 합성이 깨져도 모른다. */
    private record Holder(@NicknameFormat String nickname) {}

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static int violations(String nickname) {
        return validator.validate(new Holder(nickname)).size();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "예측가",               // 한글 음절
            "Antenna",             // 영문 대소문자
            "test1",               // 영문 + 숫자
            "테스트1",              // 한글 + 숫자
            "ㅇㅇ",                 // 호환 자모 — \p{IsHangul} 에 포함된다
            "ㅁㄴㅇㄹ",
            "삼성전자홀더99",
    })
    @DisplayName("한글·영문·숫자만으로 된 닉네임은 통과한다")
    void 허용_문자_통과(String nickname) {
        assertThat(violations(nickname)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "홍 길동",              // 가운데 공백
            " 예측가",              // 앞 공백
            "예측가 ",              // 뒤 공백
            "홍　길동",             // 전각 공백
    })
    @DisplayName("공백이 들어가면 거절한다 — 위치와 종류에 무관하게")
    void 공백_거절(String nickname) {
        assertThat(violations(nickname)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "예측가!",
            "감사합니다.",
            "ant_enna",             // 밑줄도 특수문자다
            "ant-enna",
            "ant@enna",
            "'OR'1'='1",            // 주입 시도 형태 — 애초에 저장되지 않는다
            "<script>",
            "예측가😀",              // 이모지
    })
    @DisplayName("특수문자·이모지가 들어가면 거절한다")
    void 특수문자_거절(String nickname) {
        assertThat(violations(nickname)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "예측가​",         // 제로폭 공백 — 눈에 같은 닉네임을 여럿 만든다
            "예측가‮",         // RTL override — 표시 순서를 뒤집는다
            "예측가﻿",         // BOM
            "예측\t가",             // 탭
            "예측\n가",             // 개행 — trim() 을 통과해 로그를 위조한다
    })
    @DisplayName("눈에 보이지 않는 문자를 거절한다 — 허용 목록에 없으므로 열거하지 않아도 걸린다")
    void 보이지_않는_문자_거절(String nickname) {
        assertThat(violations(nickname)).isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "аdmin",           // 첫 글자가 키릴 а(U+0430) — 라틴 a 가 아니다
            "𝗮dmin",     // 수학 볼드 𝗮
            "ａｄｍｉｎ", // 전각 라틴 ａｄｍｉｎ — Script=Latin 이라 a-zA-Z 로 적어야 걸린다
    })
    @DisplayName("라틴·한글이 아닌 스크립트로 사칭하는 닉네임을 거절한다")
    void 사칭_스크립트_거절(String nickname) {
        assertThat(violations(nickname)).isPositive();
    }
}
