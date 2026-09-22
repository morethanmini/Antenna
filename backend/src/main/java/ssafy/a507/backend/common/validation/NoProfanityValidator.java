package ssafy.a507.backend.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import lombok.RequiredArgsConstructor;
import ssafy.a507.backend.common.text.ProfanityFilter;

/**
 * {@link NoProfanity} 의 검증자. 스프링 빈({@link ProfanityFilter})을 생성자로 받는다 —
 * Boot 가 {@code SpringConstraintValidatorFactory} 로 만들어 주므로 {@code @Valid} 경로에서 그냥 된다.
 * 스프링 없이 {@code Validation.buildDefaultValidatorFactory()} 로 검증하면 HV000064 로 실패하니 테스트는 팩토리를 손으로 준다.
 *
 * <p>null·빈 값은 통과시킨다 — 필수 여부는 {@code @NotBlank} 몫이다.
 */
@RequiredArgsConstructor
public class NoProfanityValidator implements ConstraintValidator<NoProfanity, String> {

    private final ProfanityFilter profanityFilter;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return !profanityFilter.contains(value);
    }
}
