package ssafy.a507.backend.domain.event.service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.account.repository.UserRepository;
import ssafy.a507.backend.domain.event.dto.EventLeaderboardResponse;
import ssafy.a507.backend.domain.event.dto.EventLeaderboardResponse.Item;
import ssafy.a507.backend.domain.event.dto.EventPhase;
import ssafy.a507.backend.domain.event.entity.PredictionEvent;
import ssafy.a507.backend.domain.event.entity.PredictionEventResult;
import ssafy.a507.backend.domain.event.repository.PredictionEventRepository;
import ssafy.a507.backend.domain.event.repository.PredictionEventResultRepository;

/**
 * 회차 순위 조회 — ANT-EVENT-06, 화면 J-01. 작업명세 v1.2 §4 · §8.
 *
 * <p><b>굳히는 일은 여기서 하지 않는다.</b> {@code prediction_event_results} 는 정산 러너가 끝난 뒤
 * {@code EventResultService.freeze} 가 한 번 쓴다(ANT-EVENT-03). 이 서비스는 읽기만 한다 — 쓰는 곳이
 * 둘이면 "어느 쪽 숫자가 맞나" 를 물어야 한다.
 *
 * <p><b>정산 전에는 순위를 내리지 않는다</b>(09-16 결정). 봉인 뒤라도 승패를 모르기 때문이다. 지금
 * 배당률로 계산한 값은 "이기면 얼마" 일 뿐인데 화면에 순위로 뜨면 사용자는 현재 등수로 읽는다.
 * 서버가 현재가로 임시 판정하는 것은 더 나쁘다 — 월요일 종가가 아직 안 나왔다.
 */
@Service
@RequiredArgsConstructor
public class EventLeaderboardService {

    /** 수익률 소수 자릿수. 홈의 배당률과 같은 규칙이다. */
    private static final int RATE_SCALE = 2;
    private static final int DEFAULT_SIZE = 20;
    private static final int MAX_SIZE = 100;

    private static final String NOTICE_BEFORE = "접수가 시작되면 참가자가 표시됩니다.";
    private static final String NOTICE_ENTRY = "순위는 정산이 끝난 뒤 공개됩니다.";
    private static final String NOTICE_SEALED = "순위는 화요일 13:30 결과 공개 뒤 나옵니다.";

    private final PredictionEventRepository events;
    private final PredictionEventResultRepository results;
    private final UserRepository users;
    private final CurrentUserProvider currentUserProvider;

    @Transactional(readOnly = true)
    public EventLeaderboardResponse of(Long eventId, String cursor, Integer size) {
        PredictionEvent event =
                events.findById(eventId)
                        .orElseThrow(() -> new BusinessException(ErrorCode.EVENT_NOT_FOUND, "eventId"));
        EventPhase phase = EventPhase.of(event.getStatus());
        if (phase != EventPhase.SETTLED) {
            return new EventLeaderboardResponse(phase, noticeOf(phase), List.of(), null, null);
        }

        // ponytail: 자격자 전부를 읽고 자바에서 자른다. 회차가 한 번뿐이고 참가자가 수십~수백이라
        // 페이지마다 rank 범위 쿼리를 새로 만들 값이 없다. 참가자가 수천이 되면 rank > :cursor 쿼리로.
        List<PredictionEventResult> ranked = results.findRanked(eventId);
        int from = offsetOf(cursor, ranked);
        int limit = sizeOf(size);
        List<PredictionEventResult> page =
                ranked.subList(Math.min(from, ranked.size()), Math.min(from + limit, ranked.size()));

        Long viewerId = currentUserProvider.currentUserId();
        // 자격 미달·미참가는 me 가 null 이다(§4) — 순위 밖이라 보여줄 등수가 없다. 자기 성적은
        // GET /events/{id}/me 가 자격과 무관하게 내린다.
        PredictionEventResult mine =
                viewerId == null
                        ? null
                        : results.findByEventIdAndUserId(eventId, viewerId)
                                .filter(PredictionEventResult::isQualified)
                                .orElse(null);

        Map<Long, String> nicknames = nicknamesOf(page, mine);
        List<Item> items = new ArrayList<>(page.size());
        for (PredictionEventResult r : page) {
            items.add(itemOf(r, nicknames));
        }

        String nextCursor =
                from + limit < ranked.size() ? String.valueOf(page.get(page.size() - 1).getRank()) : null;
        return new EventLeaderboardResponse(
                phase, null, items, mine == null ? null : itemOf(mine, nicknames), nextCursor);
    }

    private static String noticeOf(EventPhase phase) {
        return switch (phase) {
            case BEFORE -> NOTICE_BEFORE;
            case ENTRY -> NOTICE_ENTRY;
            case SEALED -> NOTICE_SEALED;
            case SETTLED -> null;
        };
    }

    /**
     * 커서는 <b>앞 페이지의 마지막 등수</b>다. 값이 이상하면(숫자가 아니거나 범위 밖) 처음부터 준다 —
     * 400 으로 돌려보내면 화면이 오래된 커서 하나로 목록을 영영 못 연다.
     */
    private static int offsetOf(String cursor, List<PredictionEventResult> ranked) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        int afterRank;
        try {
            afterRank = Integer.parseInt(cursor.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        for (int i = 0; i < ranked.size(); i++) {
            Integer rank = ranked.get(i).getRank();
            if (rank != null && rank > afterRank) {
                return i;
            }
        }
        return ranked.size();
    }

    private static int sizeOf(Integer size) {
        if (size == null || size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** 닉네임은 묶어 읽는다 — 줄마다 물으면 한 페이지에 스무 번이다. */
    private Map<Long, String> nicknamesOf(List<PredictionEventResult> page, PredictionEventResult mine) {
        Set<Long> ids = new HashSet<>();
        for (PredictionEventResult r : page) {
            ids.add(r.getKey().getUserId());
        }
        if (mine != null) {
            ids.add(mine.getKey().getUserId());
        }
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> byId = new HashMap<>();
        for (User user : users.findAllById(ids)) {
            byId.put(user.getId(), user.getNickname());
        }
        return byId;
    }

    private static Item itemOf(PredictionEventResult r, Map<Long, String> nicknames) {
        return Item.of(r, nicknames.get(r.getKey().getUserId()), profitRate(r));
    }

    /**
     * {@code net ÷ staked × 100}. 서버가 계산하는 이유는 표기를 한 곳에 두려는 것이다 — 프론트가 정수
     * 나눗셈으로 만들면 −0 이 생기고 반올림도 갈린다. {@code staked} 가 0 인 행은 없지만(배팅이 있어야
     * 결과 행이 생긴다) 0 으로 나누지 않는 것을 코드로 남긴다.
     */
    private static BigDecimal profitRate(PredictionEventResult r) {
        BigInteger staked = r.getStaked();
        if (staked == null || staked.signum() == 0) {
            return null;
        }
        return new BigDecimal(r.getNet())
                .multiply(BigDecimal.valueOf(100))
                .divide(new BigDecimal(staked), RATE_SCALE, RoundingMode.HALF_UP);
    }
}
