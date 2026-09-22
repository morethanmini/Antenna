package ssafy.a507.backend.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 비속어 포함 금지 (ANT-COMMUNITY-09) — 사용자가 쓰고 남에게 보이는 문자열 필드에 붙인다
 * (닉네임·소개·글·댓글·리포트·근거·채널 라벨).
 *
 * <p><b>화면이 먼저 거른다.</b> 프론트가 같은 사전·같은 정규화로 제출 직전에 검사해 요청을 보내지 않으므로,
 * 여기까지 오는 위반은 API 를 직접 부른 우회뿐이다. 그래서 별도 오류 코드를 두지 않는다 —
 * {@code @Size} 와 같은 경로로 400 {@code INVALID_REQUEST} + field + 이 message 가 나간다.
 *
 * <p>마스킹이 아니라 거절인 이유: 리포트 제목·본문, 예측 근거는 서명·온체인 봉인 재료라 서버가 글자를 바꾸면 검산이 깨진다.
 */
@Documented
@Constraint(validatedBy = NoProfanityValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoProfanity {

    /** 프론트 {@code PROFANITY_MESSAGE} 와 같은 문구. 우회 요청이 400 으로 돌아와도 화면과 같은 말이 뜬다. */
    String message() default "사용할 수 없는 표현이 포함되어 있습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
