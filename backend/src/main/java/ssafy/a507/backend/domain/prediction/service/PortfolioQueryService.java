package ssafy.a507.backend.domain.prediction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.domain.prediction.dto.PortfolioResponse;
import ssafy.a507.backend.domain.prediction.entity.Prediction;
import ssafy.a507.backend.domain.prediction.repository.PredictionRepository;

/**
 * 예측 포트폴리오 (ANT-PRED-06 · 화면 C-04).
 *
 * <p>집계 셋을 DB 에서 받아 조립하기만 한다 — 상태별 수, 판정된 것의 평균 오차, 체인에 박힌 커밋 수.
 * 예측을 통째로 읽어 자바에서 세지 않는다. 예측은 사람마다 계속 늘고 화면에 나가는 것은 숫자 여덟 개다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PortfolioQueryService {

    private static final int RATE_SCALE = 2;

    private final PredictionRepository predictions;

    public PortfolioResponse mine(Long userId) {
        Map<Prediction.Status, Long> counts = countsByStatus(userId);
        long hit = counts.getOrDefault(Prediction.Status.HIT, 0L);
        long miss = counts.getOrDefault(Prediction.Status.MISS, 0L);
        /* BASE 와 OPEN 을 합쳐 "대기" 하나로 낸다 — 둘의 차이는 판정 배치가 열었느냐일 뿐이고,
           화면은 아직 결과가 안 나온 예측을 한 덩어리로 보여준다(설계서 §4 C-04). */
        long pending =
                counts.getOrDefault(Prediction.Status.BASE, 0L)
                        + counts.getOrDefault(Prediction.Status.OPEN, 0L);
        long settled = hit + miss;

        long anchored = predictions.countAnchored(userId);
        return new PortfolioResponse(
                settled + pending,
                hit,
                miss,
                pending,
                hitRate(hit, settled),
                // 판정이 없으면 평균도 없다. 0 으로 채우면 "오차 0%" 라는 거짓말이 된다
                settled == 0 ? null : scaled(predictions.avgErrorOfSettled(userId).orElse(null)),
                anchored,
                settled + pending - anchored);
    }

    private Map<Prediction.Status, Long> countsByStatus(Long userId) {
        Map<Prediction.Status, Long> counts = new EnumMap<>(Prediction.Status.class);
        for (Object[] row : predictions.countMineByStatus(userId)) {
            counts.put((Prediction.Status) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /** 적중률 % — 분모는 <b>판정된 것</b>이다. 대기를 분모에 넣으면 오래 걸릴수록 적중률이 떨어진다. */
    private static BigDecimal hitRate(long hit, long settled) {
        if (settled == 0) {
            return null;
        }
        return BigDecimal.valueOf(hit)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(settled), RATE_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal scaled(BigDecimal value) {
        return value == null ? null : value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    }
}
