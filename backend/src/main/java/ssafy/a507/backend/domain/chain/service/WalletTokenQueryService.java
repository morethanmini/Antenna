package ssafy.a507.backend.domain.chain.service;

import java.math.BigInteger;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.chain.dto.WalletBalanceResponse;
import ssafy.a507.backend.domain.chain.dto.WalletLedgerResponse;
import ssafy.a507.backend.domain.chain.entity.TokenLedger;
import ssafy.a507.backend.domain.chain.indexer.TokenIndexer;
import ssafy.a507.backend.domain.chain.relay.TokenReason;
import ssafy.a507.backend.domain.chain.repository.TokenLedgerRepository;
import ssafy.a507.backend.domain.monetize.repository.AdBannerRepository;

/**
 * 지갑 잔액·원장 조회 (ANT-TOKEN-04, 화면 H-01).
 *
 * <p>읽기만 한다. <b>체인에 묻지 않는다</b> — 잔액은 원장(token_ledger) 합이다. 원장은 토큰 인덱서가 체인 이벤트를 옮긴 사본이라
 * "원천은 온체인, DB 는 캐시" 이고, 얼마나 최신인지는 {@code syncedAt}(인덱서가 체인 끝까지 읽은 시각)으로 알린다.
 * 체인 {@code balanceOf} 를 매 요청 부르지 않는 이유: 광고·슬롯 초과가 결제 판단에 원장 합을 쓴다 — 화면 숫자와 판단 숫자가 같아야 하고,
 * RPC 가 느리거나 죽어도 지갑 화면은 떠야 한다.
 *
 * <p>지갑을 연동하지 않은 회원도 에러가 아니다 — 잔액 "0", 원장은 빈 목록.
 */
@Service
@RequiredArgsConstructor
public class WalletTokenQueryService {

    /** PredictToken 이 정한 이름. 컨트랙트 값이라 설정으로 받지 않는다. */
    static final String SYMBOL = "ANT";

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    /**
     * 집계 구간. 화면 문구("30일간")와 같은 숫자라 여기 한 곳에만 둔다.
     *
     * <p>거래일이 아니라 <b>달력일</b>이다 — 토큰은 장이 열린 날에만 오가는 것이 아니다(구독 갱신·광고·보너스).
     */
    private static final int FLOW_WINDOW_DAYS = 30;

    private final TokenLedgerRepository ledger;
    private final TokenIndexer indexer;
    private final AdBannerRepository banners;

    @Transactional(readOnly = true)
    public WalletBalanceResponse balance(Long userId) {
        /* 두 합의 기준 시각을 한 번만 읽는다. 각자 now() 를 부르면 두 쿼리의 구간이 미세하게 달라져,
           그 틈에 들어온 행 하나가 한쪽에만 잡힌다. */
        Instant from = Instant.now().minus(FLOW_WINDOW_DAYS, ChronoUnit.DAYS);
        BigInteger earned = ledger.sumEarnedSince(userId, from).orElse(BigInteger.ZERO);
        /* 나간 합은 음수로 돌아온다. 화면이 부호를 정하도록 여기서 양수로 뒤집는다 */
        BigInteger spent = ledger.sumSpentSince(userId, from).orElse(BigInteger.ZERO).negate();
        return new WalletBalanceResponse(
                ledger.balanceOf(userId).toString(),
                SYMBOL,
                indexer.lastSyncedAt().orElse(null),
                earned.toString(),
                spent.toString(),
                banners.sumPendingPrice(userId).toString());
    }

    /**
     * 최신 행부터. 커서는 마지막으로 받은 id — 그보다 작은 id 를 다음 페이지로 준다(커밋 원장 목록과 같은 규칙).
     *
     * @param reason null 이면 전체
     */
    @Transactional(readOnly = true)
    public WalletLedgerResponse ledger(Long userId, TokenReason reason, Long cursor, Integer size) {
        int pageSize = pageSize(size);
        List<TokenLedger> found =
                ledger.findPage(userId, reason == null ? null : reason.name(), cursor, Limit.of(pageSize + 1));
        boolean hasNext = found.size() > pageSize;
        List<TokenLedger> page = hasNext ? found.subList(0, pageSize) : found;
        List<WalletLedgerResponse.Item> items =
                page.stream()
                        .map(l -> new WalletLedgerResponse.Item(
                                l.getDelta().toString(),
                                l.getReason(),
                                l.getChainEvent() == null ? null : l.getChainEvent().getTxHash(),
                                l.getCreatedAt()))
                        .toList();
        Long nextCursor = hasNext ? page.get(page.size() - 1).getId() : null;
        return new WalletLedgerResponse(items, nextCursor, hasNext);
    }

    private int pageSize(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }
}
