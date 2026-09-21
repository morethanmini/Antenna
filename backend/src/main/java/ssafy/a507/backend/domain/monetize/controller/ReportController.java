package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.idempotency.IdempotencyStore;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.monetize.dto.ReportCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.ReportCreateResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportDetailResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportFeedResponse;
import ssafy.a507.backend.domain.monetize.dto.ReportProofResponse;
import ssafy.a507.backend.domain.monetize.service.ReportProofService;
import ssafy.a507.backend.domain.monetize.service.ReportService;

/** 리포트 — ANT-COMMUNITY-01. 명세 §1 의 {@code Base /api/v1} 을 경로에 직접 적는다. */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final String CREATE_ENDPOINT = "POST /reports";

    private final ReportService reportService;
    private final ReportProofService reportProofService;
    private final IdempotencyStore idempotencyStore;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 리포트 발행. 명세 §1 의 멱등성 필수 대상 8개 중 하나라 Idempotency-Key 를 받는다.
     *
     * <p>리포트에는 삭제 API 가 없다. 타임아웃 후 클라이언트가 한 번 재시도하면 같은 리포트가
     * 두 건 남고 지울 방법이 없다 — 게다가 발행 알림이 구독자 전원에게 두 번 간다.
     * {@code POST /posts} 와 같은 형태로, 멱등 저장소에는 응답 JSON 이 아니라 id 하나만 넣는다.
     */
    @PostMapping
    public ResponseEntity<ReportCreateResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReportCreateRequest request) {

        Long userId = currentUserProvider.currentUserId();
        String reportId = idempotencyStore.execute(
                userId,
                CREATE_ENDPOINT,
                idempotencyKey,
                /* 서명은 해시에 넣지 않는다(2026-09-21). 전에는 request.toString() 이라 서명까지 들어갔다 — 화면은
                   재시도마다 nonce 를 새로 받아 다시 서명하므로, 발행은 됐는데 응답만 잃은 재시도가 409 로 막혔다.
                   막힌 사용자가 제목을 한 글자 고치면 화면이 새 키를 내어(ReportNew edited) 같은 리포트가 두 번
                   발행된다 — 위에 적은 바로 그 사고다. 사용자가 쓴 값만 본다. */
                IdempotencyStore.hash(
                        request.title() + "|" + request.body() + "|" + request.visibility()),
                () -> String.valueOf(reportService.create(request)));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ReportCreateResponse(Long.valueOf(reportId)));
    }

    /**
     * 전체 리포트 통합 피드.
     *
     * <p>{@code sector} 가 오면 400 이다. 출처 컬럼이 ERD 에 없어 필터를 구현할 수 없는데
     * (Jira S15P21A507-69 ⛔), 조용히 무시하면 프론트는 "반도체 칩을 눌렀고 걸러진 목록을
     * 받았다"고 믿는다. 잘못된 목록을 정답처럼 보여주는 것보다 거절하는 편이 낫다.
     * 팀이 {@code reports.sector} 신설을 확정하면 이 블록을 지우고 필터를 붙인다.
     */
    @GetMapping
    public ReportFeedResponse feed(
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {

        if (sector != null && !sector.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sector");
        }
        return reportService.feed(
                parseScope(scope), parseSort(sort), cursor, size);
    }

    /**
     * 리포트 검산 재료 (ANT-CHAIN-17, 화면 D-04).
     *
     * <p><b>판정을 내려 주지 않는다.</b> 같은지 다른지는 브라우저가 계산한다 — 서버가 "검증됨" 이라고
     * 말하면 증명이 아니다(D-03 이 세운 원칙).
     *
     * <p>리포트 자체는 막지 않는다. 봉인 해시는 제목보다 더 공개해도 되는 값이고(해시로 본문을 되돌릴 수
     * 없다), 가리는 것은 {@code salt} 하나다 — 공개 리포트는 전원, 구독자 전용은 작성자·구독자.
     */
    @GetMapping("/{reportId}/proof")
    public ReportProofResponse proof(@PathVariable Long reportId) {
        return reportProofService.proof(reportId);
    }

    @GetMapping("/{reportId}")
    public ReportDetailResponse detail(@PathVariable Long reportId) {
        return reportService.detail(reportId);
    }

    /**
     * 열거형 쿼리 파라미터는 직접 파싱한다.
     *
     * <p>스프링의 자동 변환에 맡기면 어휘에 없는 값이
     * {@code MethodArgumentTypeMismatchException} 으로 올라가 500 이 된다. 오타는 클라이언트
     * 잘못이므로 400 이어야 하고, 명세 §1 의 오류 본문 형식({@code code, message, field})도
     * 맞춰야 한다.
     */
    private ReportService.Scope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return ReportService.Scope.ALL;
        }
        try {
            return ReportService.Scope.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "scope");
        }
    }

    private ReportService.Sort parseSort(String value) {
        if (value == null || value.isBlank()) {
            return ReportService.Sort.RECENT;
        }
        try {
            return ReportService.Sort.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "sort");
        }
    }
}
