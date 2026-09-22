package ssafy.a507.backend.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 닉네임 문자 종류 제한 (ANT-COMMUNITY-09) — 한글·영문·숫자만. 공백·밑줄·문장부호·이모지를 모두 거절한다.
 * 프론트 {@code validateNickname} 이 같은 정규식을 들고 있어 입력 중에 먼저 안내한다.
 *
 * <p><b>SQL 주입 대비가 아니다.</b> 닉네임이 지나는 조회는 전부 JPA 바인드 파라미터다. 막으려는 것은
 * <b>사람이 속는 닉네임</b>이다.
 *
 * <ul>
 *   <li>동형이의어 사칭 — 키릴 {@code а}, 수학 볼드 {@code 𝗮} 로 남의 이름을 흉내 내면
 *       UNIQUE 제약은 코드포인트가 달라 통과시킨다. 신고·제재 화면에서 관리자가 대상을 잘못 짚는다.</li>
 *   <li>제로폭 문자({@code U+200B}) — 눈에 같은 닉네임을 여러 개 만든다. {@code trim()} 이 못 잡는다.</li>
 *   <li>RTL override({@code U+202E}) — 표시 순서를 뒤집어 랭킹·댓글에서 다른 글자로 읽히게 한다.</li>
 *   <li>제어문자·개행 — 문자열 <b>중간</b>의 {@code \n} 은 {@code trim()} 을 통과해 로그를 위조한다.</li>
 * </ul>
 *
 * <p>허용 목록 방식이라 위 문자들을 하나하나 열거하지 않아도 전부 걸린다 —
 * 새로 등장하는 유니코드 장난도 목록에 없으면 자동으로 막힌다.
 *
 * <p>영문을 {@code \p{IsLatin}} 이 아니라 {@code a-zA-Z} 로 적은 이유 —
 * <b>전각 라틴({@code ａｄｍｉｎ}, U+FF41~)도 Script=Latin 이다.</b> 스크립트 속성으로 받으면
 * 전각으로 {@code admin} 을 사칭할 수 있어 이 규칙을 만든 이유가 무너진다. 숫자는 Script=Common 이라 {@code 0-9} 로 따로 적는다.
 * 한글은 {@code \p{IsHangul}} — 완성형 음절과 호환 자모를 함께 덮으므로 {@code ㅇㅇ} 같은 자모 닉네임도 통과한다.
 *
 * <p><b>이미 저장된 닉네임에는 소급하지 않는다.</b> 요청 DTO 에만 붙고 DB 제약으로 내리지 않는다 —
 * 지금 특수문자 닉네임인 회원은 다음에 닉네임을 고칠 때 이 규칙을 맞추게 된다.
 */
@Documented
@Pattern(regexp = "^[\\p{IsHangul}a-zA-Z0-9]+$")
@ReportAsSingleViolation
@Constraint(validatedBy = {})
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface NicknameFormat {

    /** 프론트 {@code validateNickname} 과 같은 문구. */
    String message() default "닉네임은 한글·영문·숫자만 쓸 수 있습니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
