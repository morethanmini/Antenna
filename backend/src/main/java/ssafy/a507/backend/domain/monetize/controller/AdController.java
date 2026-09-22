package ssafy.a507.backend.domain.monetize.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.idempotency.IdempotencyStore;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.monetize.dto.AdActiveResponse;
import ssafy.a507.backend.domain.monetize.dto.AdCreateRequest;
import ssafy.a507.backend.domain.monetize.dto.AdCreateResponse;
import ssafy.a507.backend.domain.monetize.dto.AdPricingResponse;
import ssafy.a507.backend.domain.monetize.entity.AdBanner;
import ssafy.a507.backend.domain.monetize.service.AdService;

/** 스폰서드 광고 — ANT-COMMUNITY-05. */
@RestController
@RequestMapping("/api/v1/ads")
@RequiredArgsConstructor
public class AdController {

    private static final String CREATE_ENDPOINT = "POST /ads";

    private final AdService adService;
    private final IdempotencyStore idempotencyStore;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 게재 신청 — 201. 명세 §1 의 멱등성 필수 대상이다.
     *
     * <p>승인제(ANT-ADMIN-03)라 여기서는 돈이 안 나가지만 멱등 대상은 유지한다 — 재시도로 신청이 둘이 되면 자리를
     * 두 개 잡고 가용잔액도 두 배로 묶인다. 재요청에는 처음 만든 배너 id 를 그대로 돌려준다.
     *
     * <p>멱등 저장소에는 {@code adId} 만 넣는다 — 상태는 항상 PENDING 이라 저장할 필요가 없고,
     * 저장한 시점의 상태를 돌려주면 오히려 낡은 값이 된다.
     */
    @PostMapping
    public ResponseEntity<AdCreateResponse> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdCreateRequest request) {

        Long userId = currentUserProvider.currentUserId();
        /* 본문 해시에 서명을 넣지 않는다(2026-09-21). 전에는 request.toString() 이라 서명까지 들어갔다 —
           화면은 재시도마다 nonce 를 새로 받아 다시 서명하므로 "같은 키·같은 신청" 의 정당한 재시도가 전부
           IDEMPOTENCY_KEY_REUSED(409) 였고, 재생 경로는 영영 못 탔다(QA 09-21: 취소 뒤 같은 화면에서
           다시 신청하면 "이미 처리된 요청입니다"). 같은 키로 다른 광고를 찍는 것만 막으면 된다 — 사용자가
           고른 값은 이미지·주소·일수 셋이다. 구독(SubscriptionController)과 같은 판단이다.
           취소한 신청의 id 를 재생하지 않게 하는 것은 화면 몫이다 — 접수가 확정되면 키를 버린다(api/ads.ts). */
        String adId = idempotencyStore.execute(
                userId,
                CREATE_ENDPOINT,
                idempotencyKey,
                IdempotencyStore.hash(
                        request.imageFileId() + "|" + request.linkUrl() + "|" + request.days()),
                () -> String.valueOf(adService.create(userId, request).adId()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AdCreateResponse(Long.valueOf(adId), AdBanner.Status.PENDING));
    }

    /** 신청 취소 — 본인의 심사 대기 건만. 가용잔액 홀드가 풀린다. 되돌릴 게 없어 멱등 대상이 아니다. */
    @DeleteMapping("/{adId}")
    public ResponseEntity<Void> cancel(@PathVariable Long adId) {
        adService.cancel(currentUserProvider.currentUserId(), adId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/active")
    public AdActiveResponse active() {
        return adService.active();
    }

    /**
     * 게재 조건 — ANT-COMMUNITY-08. 등록 화면이 서명 전에 보여줄 금액의 출처다.
     *
     * <p>인증만 요구한다. 광고 등록 화면 자체가 로그인 뒤에 열리고, 단가는 숨기는 값이 아니다.
     */
    @GetMapping("/pricing")
    public AdPricingResponse pricing() {
        return adService.pricing();
    }
}
