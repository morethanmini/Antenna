package ssafy.a507.backend.common.error;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** 모든 오류 응답을 API 명세 §1 형식 하나로 모은다. */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, e.getField()));
    }

    /** @Valid 실패. 첫 번째 위반 필드만 내려준다 — 프론트가 한 번에 한 곳을 가리키면 된다. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        FieldError first = e.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
        String field = first == null ? null : first.getField();
        String message = first == null
                ? ErrorCode.INVALID_REQUEST.getMessage()
                : first.getDefaultMessage();
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, message, field));
    }

    /**
     * 쿼리 파라미터·경로 변수 검증 실패(@Validated). 없으면 500 으로 나가므로 본문 검증과 같은 형식으로 맞춘다.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(ConstraintViolationException e) {
        ConstraintViolation<?> first = e.getConstraintViolations().stream().findFirst().orElse(null);
        String field = null;
        String message = ErrorCode.INVALID_REQUEST.getMessage();
        if (first != null) {
            // propertyPath 는 "메서드명.파라미터명" 이라 마지막 마디만 필드로 쓴다.
            String path = first.getPropertyPath().toString();
            field = path.substring(path.lastIndexOf('.') + 1);
            message = first.getMessage();
        }
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, message, field));
    }

    /**
     * 쿼리 파라미터·경로 변수의 <b>타입 변환</b> 실패 — {@code ?size=abc}, {@code /reports/abc}.
     *
     * <p>없으면 아래 마지막 그물로 떨어져 500 이 나간다. 클라이언트가 숫자 자리에 문자를 보낸
     * 것이므로 400 이어야 하고, 명세 §1 의 오류 본문 형식도 맞춰야 한다. 컨트롤러마다 파라미터를
     * 문자열로 받아 직접 파싱하는 대신 여기서 한 번에 처리한다 — 노출된 엔드포인트가
     * ANT-COMMUNITY-01 뿐이 아니다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.debug("파라미터 타입 변환 실패", e);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, e.getName()));
    }

    /**
     * 필수 쿼리 파라미터 누락 — {@code GET /rankings} 에 {@code track} 이 없는 경우.
     *
     * <p>없으면 마지막 그물로 떨어져 500 이 나간다. 빠뜨린 쪽은 클라이언트이므로 400 이어야 한다.
     * 타입 변환 실패(위)와 같은 부류인데 한쪽만 덮여 있었다 — 필수 파라미터를 쓰는 엔드포인트 전부에 해당한다.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException e) {
        log.debug("필수 파라미터 누락", e);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST, e.getParameterName()));
    }

    /**
     * 본문을 못 읽는 경우 — 깨진 JSON, enum 에 없는 값 등.
     * 파서 메시지는 내부 타입 이름을 흘리므로 내려보내지 않는다.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException e) {
        log.debug("본문 파싱 실패", e);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ErrorResponse.of(ErrorCode.INVALID_REQUEST));
    }

    /**
     * multipart 상한 초과. 서블릿이 본문을 다 읽기 전에 끊으므로 컨트롤러까지 오지 못한다 —
     * 이 핸들러가 없으면 마지막 그물로 떨어져 500 이 나간다. 명세 §첨부·업로드는 413 이다.
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
        log.debug("업로드 용량 초과", e);
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.getStatus())
                .body(ErrorResponse.of(ErrorCode.FILE_TOO_LARGE, "file"));
    }

    /**
     * 매핑되지 않은 경로 — {@code GET /api/v1/me/portfolio} 처럼 없는 주소.
     *
     * <p>없으면 마지막 그물로 떨어져 <b>500 이 나간다</b>. 던지는 쪽이 정적 리소스 핸들러라
     * 이름이 {@code NoResourceFoundException} 이지만, 실제로는 "어느 컨트롤러도 이 경로를
     * 받지 않는다" 는 뜻이다.
     *
     * <p>두 가지가 문제였다. ① 프론트가 "없는 것" 과 "서버 고장" 을 구분하지 못한다 —
     * 오타난 경로도, 서버에서 사라진 엔드포인트도 전부 오류 화면이 된다. ② 봇·스캐너가 아무
     * 경로나 긁을 때마다 {@code ERROR} 스택트레이스가 쌓여 진짜 오류가 묻힌다.
     *
     * <p>로그는 {@code debug} 다. 클라이언트가 잘못 부른 것이지 서버 예외가 아니다 —
     * 위 타입 변환·파라미터 누락 핸들러와 같은 판단이다.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        log.debug("매핑되지 않은 경로: {}", e.getResourcePath());
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ErrorResponse.of(ErrorCode.NOT_FOUND));
    }

    /**
     * 경로는 있는데 그 메서드를 받지 않는 경우 — {@code DELETE /api/v1/predictions/1}.
     *
     * <p>없으면 마지막 그물로 떨어져 <b>500 이 나간다</b>. 위 {@code NoResourceFoundException}(404)과
     * 같은 부류이고 원인도 같다: 프레임워크가 던지는 예외라 어느 도메인 테스트도 다루지 않았다.
     *
     * <p>404 와 code 를 나눈 이유 — 클라이언트가 고칠 곳이 다르다. 404 는 주소를 고쳐야 하고
     * 405 는 메서드를 고쳐야 한다. {@code Allow} 헤더는 스프링이 이미 붙여 준다.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        log.debug("지원하지 않는 메서드: {}", e.getMethod());
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /**
     * 본문 형식을 못 읽는 경우 — {@code Content-Type} 이 없거나 {@code application/json} 이 아니다.
     *
     * <p>없으면 마지막 그물로 떨어져 <b>500 이 나간다</b>. 415 는 "본문은 받았는데 형식을 모르겠다" 이고,
     * 400 {@code INVALID_REQUEST}(위 {@code HttpMessageNotReadableException})는 "형식은 맞는데 내용이 깨졌다" 다.
     *
     * <p>짝인 {@code HttpMediaTypeNotAcceptableException}(406)은 바로 아래에서 따로 받는다.
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        log.debug("지원하지 않는 Content-Type: {}", e.getContentType());
        return ResponseEntity.status(ErrorCode.UNSUPPORTED_MEDIA_TYPE.getStatus())
                .body(ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE));
    }

    /**
     * {@code Accept} 를 만족하는 표현이 없는 경우 — {@code Accept: application/xml}.
     *
     * <p><b>본문을 담지 않는다.</b> 클라이언트가 "JSON 은 안 받는다" 고 말한 상황이라 §1 오류 본문(JSON)을
     * 쓰면 그 요구를 어긴다. 상태 코드만 돌려준다.
     *
     * <p>이 핸들러가 없어도 응답은 406 이라 겉보기에는 멀쩡했다 — <b>그래서 늦게 찾았다.</b> 실제로는
     * 마지막 그물이 잡아 {@code ERROR} 스택을 남기고 500 본문을 쓰려다, 그 본문도 같은 이유로 못 써서
     * 결국 본문 없는 406 으로 끝나고 있었다. 상태 코드만 보면 정상이고 로그만 더러워지는 모양이라
     * 이 작업의 이유 ②(스택트레이스 오염)가 여기서만 안 풀려 있었다.
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<Void> handleNotAcceptable(HttpMediaTypeNotAcceptableException e) {
        log.debug("Accept 를 만족하는 표현 없음: {}", e.getSupportedMediaTypes());
        return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).build();
    }

    /** 마지막 그물. 여기까지 온 것은 예상 못 한 오류이므로 스택을 남긴다. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }
}
