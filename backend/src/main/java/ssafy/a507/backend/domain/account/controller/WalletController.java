package ssafy.a507.backend.domain.account.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.dto.WalletLinkRequest;
import ssafy.a507.backend.domain.account.dto.WalletLinkResponse;
import ssafy.a507.backend.domain.account.dto.WalletNonceRequest;
import ssafy.a507.backend.domain.account.dto.WalletNonceResponse;
import ssafy.a507.backend.domain.account.dto.WalletStatusResponse;
import ssafy.a507.backend.domain.account.service.WalletService;
import ssafy.a507.backend.domain.chain.dto.WalletBalanceResponse;
import ssafy.a507.backend.domain.chain.dto.WalletLedgerResponse;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.service.WalletTokenQueryService;

/**
 * 지갑은 계정당 1개인 싱글턴 리소스라 경로가 단수다(API 명세 §지갑·토큰).
 * Base 는 /api/v1 이다 — 명세 헤더와 프론트 api/client.ts 가 그 값으로 고정돼 있다.
 * 잔액·원장 조회(/wallet/balance · /wallet/ledger)는 ANT-TOKEN-04 — 토큰 데이터라 서비스는 chain 도메인에 있다.
 */
@RestController
@RequestMapping("/api/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;
    private final WalletTokenQueryService walletTokenQueryService;
    private final CurrentUserProvider currentUserProvider;

    /** 지갑 연동뿐 아니라 온체인 동반 요청 전부가 쓰는 공용 창구다. scope가 nonce 칸을 고른다. */
    @PostMapping("/nonce")
    public WalletNonceResponse issueNonce(@Valid @RequestBody WalletNonceRequest request) {
        return walletService.issueNonce(currentUserProvider.currentUserId(), request.scope());
    }

    /**
     * 새 리소스를 만드는 게 아니라 기존 계정의 상태를 바꾸는 전이라서 200이다.
     * 가입 보너스 mint 는 비동기지만(ANT-TOKEN-07) 연동 자체는 여기서 끝나므로 202 로 바꾸지 않는다.
     */
    @PostMapping("/link")
    public WalletLinkResponse link(@Valid @RequestBody WalletLinkRequest request) {
        return walletService.link(currentUserProvider.currentUserId(), request);
    }

    @GetMapping
    public WalletStatusResponse status() {
        return walletService.status(currentUserProvider.currentUserId());
    }

    @GetMapping("/balance")
    public WalletBalanceResponse balance() {
        return walletTokenQueryService.balance(currentUserProvider.currentUserId());
    }

    /** reason 이 TokenReason 어휘가 아니면 전역 핸들러가 400 INVALID_REQUEST(field=reason)로 돌려준다. */
    @GetMapping("/ledger")
    public WalletLedgerResponse ledger(
            @RequestParam(required = false) TokenReason reason,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size) {
        return walletTokenQueryService.ledger(currentUserProvider.currentUserId(), reason, cursor, size);
    }
}
