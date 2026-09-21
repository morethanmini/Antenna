package ssafy.a507.backend.domain.prediction.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import ssafy.a507.backend.domain.chain.dto.ProofResponse;
import ssafy.a507.backend.domain.chain.entity.AnchorBatch;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.service.BusinessDays;

/**
 * GET /api/v1/predictions/me 의 목록 항목 (ANT-PRED-06, 화면 C-02).
 *
 * <p>명세의 아홉 필드에 {@code stockName} 을 더했다 — 목록에서 종목코드만 보여줄 수는 없고,
 * 화면이 코드마다 종목 조회를 한 번 더 하는 것을 막는다(프론트 요청, 2026-09-07).
 *
 * <p>{@code status} 는 엔티티의 네 값({@code BASE·OPEN·HIT·MISS})을 그대로 내린다.
 * 필터 파라미터의 {@code PENDING} 은 BASE·OPEN 을 묶은 조회용 어휘일 뿐이고 저장된 상태가 아니다.
 *
 * <p>{@code anchorStatus} 는 "이 예측이 블록에 올라갔는가" (프론트 요청 2026-09-09, 화면 C-02 행마다 AnchorBadge).
 * 어휘는 proof 의 {@link ProofResponse.AnchorStatus} 그대로 — 상세(C-03)와 같은 값으로 그린다. 커밋이 아직 없으면 WAITING.
 */
public record MyPredictionItemResponse(
        long id,
        String stockCode,
        String stockName,
        Prediction.Direction direction,
        BigDecimal targetPrice,
        short horizon,
        Prediction.Status status,
        Integer dday,
        BigDecimal errorRate,
        /**
         * 기준일 (ANT-PRED-11). 화면의 진행 막대가 시작일을 {@code settleDate - horizon 일} 로 되짚어 그렸는데,
         * horizon 이 영업일이 되면서 그 뺄셈이 틀어진다 — 화요일 만기 · 3일이 토요일 시작으로 나온다. 되짚지 말고 준다.
         */
        LocalDate baseDate,
        LocalDate settleDate,
        ProofResponse.AnchorStatus anchorStatus,
        /**
         * 결과 공개 예정일 (ANT-PRED-12). 화면이 "3일" 대신 이 날짜를 적는다 — 기간만 보여 주면 만기가 주말일 때
         * "3일이 지났는데 안 열린다" 로 읽힌다. 계산은 {@link BusinessDays#revealDate}. 만기일이 없으면(REPLAY) null.
         */
        LocalDate revealDate) {

    public static MyPredictionItemResponse of(Prediction p, LocalDate today, AnchorBatch batch) {
        Stock stock = p.getStock();
        return new MyPredictionItemResponse(
                p.getId(),
                stock == null ? null : stock.getCode(),
                stock == null ? null : stock.getName(),
                p.getDirection(),
                p.getTargetPrice(),
                p.getHorizon(),
                p.getStatus(),
                dday(p, today),
                p.getErrorRate(),
                p.getBaseDate(),
                p.getSettleDate(),
                ProofResponse.AnchorStatus.of(batch),
                BusinessDays.revealDate(p.getSettleDate(), p.getSettledOn()));
    }

    /**
     * 만기까지 남은 일수. 판정된 건과 만기일이 아직 없는 건(REAL 은 기준가 배치가 채우기 전)은 null 이다 —
     * 0 으로 채우면 화면이 "오늘 만기" 로 읽는다. 채널 목록·상세(ANT-PRED-05)가 같은 규칙을 써서 패키지 공개다.
     */
    static Integer dday(Prediction p, LocalDate today) {
        if (p.getSettleDate() == null
                || p.getStatus() == Prediction.Status.HIT
                || p.getStatus() == Prediction.Status.MISS) {
            return null;
        }
        return (int) ChronoUnit.DAYS.between(today, p.getSettleDate());
    }
}
