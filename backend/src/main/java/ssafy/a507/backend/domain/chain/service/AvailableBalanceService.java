package ssafy.a507.backend.domain.chain.service;

import java.math.BigInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.chain.relay.TokenRelayer;
import ssafy.a507.backend.domain.event.repository.PredictionEventBetRepository;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;
import ssafy.a507.backend.domain.monetize.repository.SubscriptionRepository;

/**
 * 가용잔액 (ANT-TOKEN-11) — 토큰을 쓰는 API 셋(배팅·구독·광고)이 <b>같은 값</b>을 본다.
 *
 * <pre>
 *   가용잔액 = 체인 잔액 − (PENDING 배팅 stake + PENDING 구독 fee + PENDING 광고 price)
 * </pre>
 *
 * <p><b>왜 체인 잔액인가</b> — 원장({@code token_ledger})은 인덱서 폴링만큼 늦다. 방금 쓴 돈이 아직 남아 보인다.
 *
 * <p><b>왜 PENDING 을 빼나</b> — PENDING 은 "전송 중이거나 전송을 기다리는 돈" 이다. 배팅은 이미 자기 PENDING 을
 * 빼고 있었는데({@code EventEntryService}), 구독·광고는 안 뺐고 <b>셋이 서로의 PENDING 을 못 봤다.</b>
 * 광고 승인제(ANT-ADMIN-03)가 들어오면서 이 구멍이 UX 로 드러난다 — 광고 심사 중인 7,000 ANT 로 배팅이 된다.
 *
 * <p><b>왜 홀드 테이블이 아닌가</b> — PENDING 리소스 자체가 홀드다. 실패하면 자기 상태를 닫으므로(FAILED · EXPIRED ·
 * REJECTED) 홀드가 저절로 풀린다. 별도 표를 두면 해제 시점 세 곳과 TTL 배치가 따라오고 좀비 홀드가 생긴다.
 * 원장 선차감도 안 된다 — 원장은 append-only 이고 "체인 이벤트의 사본" 이라 서버가 행을 넣으면 이중 차감으로 보인다.
 *
 * <p>합계는 짧은 트랜잭션에서 읽고 체인 조회는 그 밖에서 한다 — {@code balanceOf} 는 네트워크 호출이라 DB 커넥션을
 * 잡은 채로 기다리지 않는다({@code EventEntryService.retryFailedBets} 와 같은 판단). 호출자가 이미 트랜잭션 안이면
 * 그 안에 얹힌다.
 */
@Service
@RequiredArgsConstructor
public class AvailableBalanceService {

    private final TokenRelayer relayer;
    private final PredictionEventBetRepository bets;
    private final SubscriptionRepository subscriptions;
    private final AdBannerRepository banners;
    private final TransactionTemplate tx;

    /** 지갑이 없으면 {@code WALLET_NOT_LINKED} — 토큰을 쓰는 API 는 전부 지갑이 전제다. */
    public BigInteger of(User user) {
        String wallet = user.getWalletAddress();
        if (wallet == null || wallet.isBlank()) {
            throw new BusinessException(ErrorCode.WALLET_NOT_LINKED);
        }
        return of(user.getId(), wallet);
    }

    /** 엔티티 없이 id·지갑만 있을 때(재전송 배치). */
    public BigInteger of(Long userId, String wallet) {
        BigInteger held = tx.execute(s -> held(userId));
        return relayer.balanceOf(wallet).subtract(held);
    }

    /** 전송 중인 내 돈의 합. 세 도메인 어느 하나라도 빠지면 그 도메인의 PENDING 으로 다른 데서 돈을 쓸 수 있다. */
    public BigInteger held(Long userId) {
        return bets.sumPendingStake(userId)
                .add(subscriptions.sumPendingFee(userId))
                .add(banners.sumPendingPrice(userId));
    }
}
