package ssafy.a507.backend.domain.monetize.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.market.entity.IndexQuote;
import ssafy.a507.backend.domain.market.repository.IndexQuoteRepository;
import ssafy.a507.backend.domain.monetize.dto.BacktestResponse;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;
import ssafy.a507.backend.domain.account.repository.UserRepository;

/**
 * "따라 샀다면" 백테스트 — ANT-TOKEN-03, 화면 E-02.
 *
 * <p><b>계산 규칙</b> (명세가 "판정 완료 예측 기반 · 파라미터 고정"까지만 정해서 여기서 굳혔다)
 *
 * <ol>
 *   <li>대상 — 최근 {@value #MONTHS}개월 안에 <b>판정된</b>(HIT·MISS) REAL 예측. 대기 중인 건은 아직
 *       결과가 없어 넣을 수 없고, REPLAY(모의투자)는 실전 성적이 아니다.
 *   <li>한 건의 수익률 — {@code (판정가 − 기준가) / 기준가 × 100}. <b>DOWN 은 부호를 뒤집는다</b> —
 *       하락을 맞히면 번 것이다(공매도 가정). 방향을 무시하고 상승분만 세면 하락 전문 예측가가
 *       늘 손해로 나온다.
 *   <li>합치는 법 — <b>균등 배분</b>. 원금을 표본 수로 나눠 한 건씩 담았다고 본다. 그래서 누적
 *       수익률은 각 건 수익률의 평균이다. 종목별 비중을 다르게 두려면 "얼마를 걸었나"가 있어야
 *       하는데 예측에는 그 값이 없다 — 있는 것으로만 계산한다.
 *   <li>비교 기준 — 같은 구간의 KOSPI. 구간은 <b>그 사람의 첫 판정일 ~ 마지막 판정일</b>이다.
 *       3개월 전체를 쓰지 않는 이유는, 두 주만 활동한 사람을 석 달치 지수와 견주면 견준 것이
 *       아니어서다.
 * </ol>
 *
 * <p>수수료·슬리피지·배당은 없다(설계 제약: 수수료 0). 화면이 그 조건을 그대로 적어 보여준다.
 *
 * <p>ponytail: 결과를 저장하지 않고 매 요청 계산한다. 무료이고 표본이 사람당 수십 건이라 한 번에
 * 끝난다. 예측이 수천 건 쌓이는 채널이 생기면 그때 캐시한다.
 */
@Service
@RequiredArgsConstructor
public class BacktestService {

    /** 설계 제약. 화면이 이 값을 받아 "최근 3개월" 문구를 만든다 */
    static final int MONTHS = 3;

    /** 가상 원금(원). 균등 배분이라 수익률 자체는 이 값에 좌우되지 않는다 — 화면 문구용이다 */
    static final long PRINCIPAL = 10_000_000L;

    private static final int RATE_SCALE = 2;

    private final UserRepository users;
    private final PredictionRepository predictions;
    private final IndexQuoteRepository indexQuotes;

    @Transactional(readOnly = true)
    public BacktestResponse of(Long userId) {
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.USER_NOT_FOUND, "userId");
        }
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusMonths(MONTHS);

        List<Object[]> rows = predictions.backtestRows(userId, from);
        if (rows.isEmpty()) {
            // 표본이 없으면 수익률도 없다. 0 을 내리면 화면이 "손실 없음" 으로 읽는다 —
            // 그쪽은 sampleCount 0 을 보고 결과 대신 안내를 그린다.
            return new BacktestResponse(
                    MONTHS, PRINCIPAL, BigDecimal.ZERO, null, 0, Instant.now());
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Object[] row : rows) {
            sum = sum.add(profitRate((Prediction.Direction) row[0], (BigDecimal) row[1], (BigDecimal) row[2]));
        }
        BigDecimal returnRate =
                sum.divide(BigDecimal.valueOf(rows.size()), RATE_SCALE, RoundingMode.HALF_UP);

        return new BacktestResponse(
                MONTHS,
                PRINCIPAL,
                returnRate,
                benchmarkRate(userId, from),
                rows.size(),
                Instant.now());
    }

    /**
     * 한 건의 수익률 %. DOWN 은 부호를 뒤집는다 — 하락을 맞히면 번 것이다.
     *
     * <p>기준가가 0 이하인 행은 쿼리가 이미 걸렀다(0 으로 나누면 계산 전체가 죽는다).
     */
    private static BigDecimal profitRate(
            Prediction.Direction direction, BigDecimal basePrice, BigDecimal settlePrice) {
        BigDecimal change = settlePrice.subtract(basePrice)
                .multiply(BigDecimal.valueOf(100))
                .divide(basePrice, 6, RoundingMode.HALF_UP);
        return direction == Prediction.Direction.DOWN ? change.negate() : change;
    }

    /**
     * 같은 구간의 KOSPI 수익률 %. 구간은 그 사람이 실제로 판정받은 날들의 양 끝이다.
     *
     * <p>양 끝 중 하나라도 지수가 없으면 null 이다 — 0 으로 내리면 "코스피는 제자리였다"는 거짓이
     * 되고, 화면은 null 을 대시로 그린다.
     */
    private BigDecimal benchmarkRate(Long userId, LocalDate from) {
        List<Object[]> span = predictions.settledSpan(userId, from);
        if (span.isEmpty() || span.get(0)[0] == null || span.get(0)[1] == null) {
            return null;
        }
        LocalDate start = (LocalDate) span.get(0)[0];
        LocalDate end = (LocalDate) span.get(0)[1];
        if (!start.isBefore(end)) {
            // 하루 안에 다 판정됐다. 구간이 없으니 견줄 것도 없다.
            return null;
        }
        Optional<BigDecimal> open = closeOnOrBefore(start);
        Optional<BigDecimal> close = closeOnOrBefore(end);
        if (open.isEmpty() || close.isEmpty() || open.get().signum() <= 0) {
            return null;
        }
        return close.get().subtract(open.get())
                .multiply(BigDecimal.valueOf(100))
                .divide(open.get(), RATE_SCALE, RoundingMode.HALF_UP);
    }

    /** 그 날짜의 종가. 휴장이면 직전 거래일 것을 쓴다 — 판정일이 늘 거래일인 것은 아니다. */
    private Optional<BigDecimal> closeOnOrBefore(LocalDate date) {
        return indexQuotes
                .findFirstByIndexCodeAndTradeDateLessThanEqualOrderByTradeDateDesc(
                        IndexQuote.IndexCode.KOSPI, date)
                .map(IndexQuote::getClose);
    }
}
