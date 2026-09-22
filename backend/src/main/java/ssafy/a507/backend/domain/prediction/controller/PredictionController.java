package ssafy.a507.backend.domain.prediction.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
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
import ssafy.a507.backend.common.idempotency.IdempotencyStore;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.prediction.dto.PredictionCreateRequest;
import ssafy.a507.backend.domain.prediction.dto.PredictionDetailResponse;
import ssafy.a507.backend.domain.prediction.dto.PredictionFeedResponse;
import ssafy.a507.backend.domain.prediction.dto.PredictionSlotResponse;
import ssafy.a507.backend.domain.prediction.service.MyPredictionQueryService;
import ssafy.a507.backend.domain.prediction.service.PredictionRegisterService;
import ssafy.a507.backend.domain.prediction.service.PredictionRegisterService.Registration;
import ssafy.a507.backend.domain.prediction.service.PredictionViewService;

/** 예측 등록·슬롯 (ANT-PRED-01). 내 예측 목록({@code /me})은 {@code MyPredictionController} — 같은 base path 를 나눠 쓴다. */
@RestController
@RequestMapping("/api/v1/predictions")
@RequiredArgsConstructor
public class PredictionController {

    private static final String CREATE_ENDPOINT = "POST /predictions";

    private final PredictionRegisterService registerService;
    private final PredictionViewService viewService;
    private final IdempotencyStore idempotencyStore;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 예측 등록(봉인). 명세 §1 멱등성 필수 대상이라 {@code Idempotency-Key} 를 받는다 — 예측에는 삭제 API 가 없고 슬롯은
     * 되돌아오지 않는다. 타임아웃 뒤 같은 키로 재시도하면 처음 만든 예측의 id 로 같은 201 을 다시 만든다(서명 검증도 다시
     * 타지 않으니 nonce 가 이미 사라졌어도 된다). 저장하는 건 {@code PostController} 와 같이 id 하나뿐이다.
     */
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PredictionCreateRequest request) {
        Long userId = currentUserProvider.currentUserId();
        /* 멱등 저장소에는 "예측id" 또는 "예측id:작업id" 만 남긴다. 응답 본문을 통째로 넣지 않는 이유 — 202 의 status 는
           그사이 인덱서가 바꿨을 수 있어 재시도마다 다시 읽는 것이 맞다(구독 202 와 같다). */
        String stored = idempotencyStore.execute(
                userId,
                CREATE_ENDPOINT,
                idempotencyKey,
                /* 서명은 해시에 넣지 않는다(2026-09-21). 전에는 request.toString() 이라 서명까지 들어갔다 — 화면은
                   재시도마다 nonce 를 새로 받아 다시 서명하므로, 접수는 됐는데 응답만 잃은 재시도가 처음 만든 예측을
                   돌려받지 못하고 IDEMPOTENCY_KEY_REUSED(409) 였다. 사용자가 정한 값만 본다(noteSalt 포함) —
                   구독·광고와 같은 판단이다. */
                IdempotencyStore.hash(String.join("|",
                        request.stockCode(),
                        String.valueOf(request.direction()),
                        String.valueOf(request.targetPrice()),
                        String.valueOf(request.horizon()),
                        request.note(),
                        request.noteSalt(),
                        String.valueOf(request.evidencePointIds()))),
                () -> encode(registerService.create(userId, request)));
        Registration registration = decode(stored);
        if (registration.operationId() == null) {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(registerService.created(userId, registration.predictionId()));
        }
        // 슬롯 초과 — 소각으로 접수(ANT-PRED-10). 폴링 창구를 Location 으로 알린다(명세 §예측 202).
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(HttpHeaders.LOCATION, "/api/v1/operations/" + registration.operationId())
                .body(registerService.accepted(userId, registration.operationId()));
    }

    private static String encode(Registration r) {
        return r.operationId() == null ? String.valueOf(r.predictionId()) : r.predictionId() + ":" + r.operationId();
    }

    private static Registration decode(String stored) {
        int at = stored.indexOf(':');
        return at < 0
                ? new Registration(Long.parseLong(stored), null)
                : new Registration(Long.parseLong(stored.substring(0, at)), stored.substring(at + 1));
    }

    /** 오늘 무료 슬롯. 등록 화면이 서명 전에 부른다. */
    @GetMapping("/slots")
    public PredictionSlotResponse slots() {
        return registerService.slots(currentUserProvider.currentUserId());
    }

    /**
     * 예측 피드 (ANT-PRED-05, 화면 F-01 예측 탭). 구독 중인 채널들의 예측을 한 줄로 모은다.
     *
     * <p>{@code scope}·{@code status} 어휘는 각각 리포트 피드({@code GET /reports})·내 예측({@code /predictions/me})과
     * 같다. 한 화면의 두 탭이 다른 말을 쓰면 안 되고, 같은 목록을 세 화면이 다른 필터로 거르면 안 된다.
     */
    @GetMapping("/feed")
    public PredictionFeedResponse feed(
            @RequestParam(required = false) PredictionViewService.Scope scope,
            @RequestParam(required = false) MyPredictionQueryService.StatusFilter status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return viewService.feed(
                scope == null ? PredictionViewService.Scope.ALL : scope, status, cursor, size);
    }

    /**
     * 예측 상세 (ANT-PRED-05). 403 을 쓰지 않는다 — 공개 규칙이 필드 단위다({@link PredictionViewService}).
     * {@code /slots}·{@code /me}·{@code /feed} 같은 고정 경로가 이 패턴보다 먼저 잡힌다.
     */
    @GetMapping("/{predictionId}")
    public PredictionDetailResponse detail(@PathVariable long predictionId) {
        return viewService.detail(predictionId);
    }
}
