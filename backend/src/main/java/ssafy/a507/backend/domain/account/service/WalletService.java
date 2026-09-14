package ssafy.a507.backend.domain.account.service;

import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.SignatureGuard;
import ssafy.a507.backend.common.security.SignatureNonceStore;
import ssafy.a507.backend.common.security.SignatureScope;
import ssafy.a507.backend.domain.account.dto.WalletLinkRequest;
import ssafy.a507.backend.domain.account.dto.WalletLinkResponse;
import ssafy.a507.backend.domain.account.dto.WalletNonceResponse;
import ssafy.a507.backend.domain.account.dto.WalletStatusResponse;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.chain.service.SignupBonusService;

/**
 * SSAFY WALLET 연동 (ANT-AUTH-04). 연동 후에야 예측 등록과 온체인 동반 요청이 열린다.
 * 연동하면 가입 보너스를 보낸다 (ANT-TOKEN-07).
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    private final UserRepository userRepository;
    private final SignatureNonceStore nonceStore;
    private final SignatureGuard signatureGuard;
    private final SignupBonusService signupBonusService;
    /** 연동과 보너스 전송의 경계를 손으로 나눈다 — 전송은 트랜잭션 밖이어야 한다({@code TokenOperationService} 계약). */
    private final TransactionTemplate tx;

    /**
     * 같은 칸에 재발급하면 그 칸의 이전 nonce만 죽는다. 칸이 scope로 나뉘어 있어
     * 예측 등록 서명을 띄워 둔 채 구독 결제를 시작해도 서로를 죽이지 않는다.
     * chainId를 같이 돌려주는 이유는 WalletNonceResponse 주석 참고.
     */
    public WalletNonceResponse issueNonce(Long userId, SignatureScope scope) {
        return new WalletNonceResponse(nonceStore.issue(userId, scope), signatureGuard.chainId());
    }

    /**
     * 연동 → 가입 보너스 mint 전송.
     *
     * <p>{@code @Transactional} 을 붙이지 않는다 (ANT-TOKEN-07). 지갑 저장과 보너스 작업을 한 커밋으로 끝낸 뒤 트랜잭션 밖에서
     * mint 를 보낸다 — 반대로 하면 체인에 나간 뒤 연동이 롤백돼 "보너스는 나갔는데 연동이 없는" 상태가 생긴다.
     * 전송이 실패해도 연동은 성공이다(작업 FAILED → 재시도 배치). 붙이면 전송이 활성 트랜잭션 안이라 거부된다.
     */
    public WalletLinkResponse link(Long userId, WalletLinkRequest request) {
        Linked linked = tx.execute(status -> linkAndAccept(userId, request));
        if (linked.bonusOperationId() == null) {
            return new WalletLinkResponse(linked.address(), null);
        }
        signupBonusService.send(linked.bonusOperationId());
        return new WalletLinkResponse(
                linked.address(),
                new WalletLinkResponse.Bonus(
                        linked.bonusOperationId(), signupBonusService.amount().toString()));
    }

    /** @param bonusOperationId 체인이 꺼져 있어 보너스 작업을 안 만들었으면 null */
    private record Linked(String address, String bonusOperationId) {}

    /**
     * 연동 순서가 곧 방어 순서다.
     * ① 이미 연동했나 ② 남이 쓰는 주소인가 ③ 서명이 맞나 — 성공할 수 없는 요청에 nonce를 태우지 않는다.
     */
    private Linked linkAndAccept(Long userId, WalletLinkRequest request) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        if (user.getWalletAddress() != null) {
            throw new BusinessException(ErrorCode.WALLET_ALREADY_LINKED);
        }

        String address = request.address().toLowerCase(Locale.ROOT);
        if (userRepository.existsByWalletAddress(address)) {
            throw new BusinessException(ErrorCode.WALLET_ALREADY_LINKED, "address");
        }

        String recovered = signatureGuard.recover(userId, request);
        // 서명한 지갑과 등록하려는 주소가 같아야 한다. 남의 주소를 자기 계정에 붙이는 걸 막는다.
        if (!address.equals(recovered)) {
            throw new BusinessException(ErrorCode.SIGNER_MISMATCH, "signature");
        }

        user.linkWallet(address);
        try {
            // 선검사와 커밋 사이에 낀 동시 요청은 UQ가 잡는다. flush를 당겨 여기서 받아 낸다.
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(ErrorCode.WALLET_ALREADY_LINKED, "address");
        }
        // 지갑 저장과 같은 커밋 — 이 커밋 전에는 재시도 배치가 이 회원의 지갑을 못 보므로 보너스가 두 번 접수될 창이 없다.
        return new Linked(address, signupBonusService.acceptIfEligible(user));
    }

    @Transactional(readOnly = true)
    public WalletStatusResponse status(Long userId) {
        User user =
                userRepository
                        .findById(userId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHENTICATED));
        String address = user.getWalletAddress();
        return new WalletStatusResponse(address != null, address);
    }
}
