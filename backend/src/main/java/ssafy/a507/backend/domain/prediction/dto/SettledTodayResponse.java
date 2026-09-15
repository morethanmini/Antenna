package ssafy.a507.backend.domain.prediction.dto;

import java.math.BigDecimal;
import java.util.List;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * GET /api/v1/stocks/{code}/predictions/settled-today 200 응답 (ANT-PRED-07, 종목 상세 머리).
 *
 * <p>판정 완료만 오므로 잠금 칸이 없다 — 판정 후 방향·목표가·결과는 전체 공개다. 근거 본문은 싣지 않는다.
 * 없으면 {@code items} 가 빈 목록이다(휴장일·배치 전에는 늘 비어 있다).
 */
public record SettledTodayResponse(List<Item> items) {

    public record Item(
            long id,
            Author author,
            Prediction.Direction direction,
            BigDecimal targetPrice,
            Prediction.Status status,
            BigDecimal errorRate) {

        public static Item from(Prediction p, String avatarUrl) {
            return new Item(
                    p.getId(),
                    new Author(p.getUser().getId(), p.getUser().getNickname(), avatarUrl),
                    p.getDirection(),
                    p.getTargetPrice(),
                    p.getStatus(),
                    p.getErrorRate());
        }
    }

    /**
     * 작성자. {@code avatarUrl} 은 프로필 사진이다 — 2026-09-15부터 실제 값이 실린다(그 전에는 users 에
     * 사진 칸이 없어 항상 null 이었다). 안 올렸으면 null 이고 화면이 기본 그림을 쓴다. 옛 주석: 칸을 두는 이유는 프론트 타입이
     * 이미 이 모양을 기다리고 있어서다(null 이면 기본 얼굴). 사진 칸은 스키마 변경이라 이 이슈 범위 밖이다.
     */
    public record Author(Long userId, String nickname, String avatarUrl) {}
}
