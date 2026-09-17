package ssafy.a507.backend.domain.event.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;

/**
 * GET /api/v1/events 200 응답 (ANT-EVENT-04, 작업명세 v1.2 §3-1).
 *
 * <p>배너·탭이 "지금 대회가 있나, 언제 열리나"만 묻는 자리라 종목·풀은 싣지 않는다 — 그건
 * {@code GET /events/{id}} 몫이다. 목록이라 커서도 없다(이번 회차는 하나뿐이고, 늘어도 몇 건이다).
 */
public record EventListResponse(List<Item> items) {

    public record Item(
            Long eventId,
            /** 화면 주소에 쓰는 이름. 없을 수 있다(운영 점검용 회차) */
            String slug,
            String title,
            PredictionEvent.Status status,
            /** 화면용으로 접은 단계. 프론트가 온체인 진행 순서를 몰라도 되게 한다 */
            EventPhase phase,
            Instant opensAt,
            Instant sealsAt,
            LocalDate baseDate,
            LocalDate settleDate) {

        public static Item of(PredictionEvent e) {
            return new Item(
                    e.getId(),
                    e.getSlug(),
                    e.getTitle(),
                    e.getStatus(),
                    EventPhase.of(e.getStatus()),
                    e.getOpensAt(),
                    e.getSealsAt(),
                    e.getBaseDate(),
                    e.getSettleDate());
        }
    }
}
