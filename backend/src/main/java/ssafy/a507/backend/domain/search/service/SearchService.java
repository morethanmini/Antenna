package ssafy.a507.backend.domain.search.service;

import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ssafy.a507.backend.common.error.BusinessException;
import ssafy.a507.backend.common.error.ErrorCode;
import ssafy.a507.backend.domain.account.entity.User;
import ssafy.a507.backend.domain.common.Track;
import ssafy.a507.backend.domain.market.service.StockService;
import ssafy.a507.backend.domain.monetize.repository.ReportRepository;
import ssafy.a507.backend.domain.ranking.repository.RankingRepository;
import ssafy.a507.backend.domain.ranking.service.RankingFilterKey;
import ssafy.a507.backend.domain.search.dto.SearchResponse;

/**
 * 통합 검색 (ANT-DATA-05) — 사이드바·상단 검색창의 여러 타입 얕은 검색.
 *
 * <p>타입별 상위 N건만 준다. 필터·정렬·페이징은 여기서 하지 않는다 — 그건 각 타입 전용 목록
 * (GET /stocks · /rankings · /reports)의 책임이다(명세 §홈·시세).
 *
 * <ul>
 *   <li>종목 — 종목명·섹터 부분일치 또는 종목코드 앞자리, 시가총액 큰 순
 *   <li>예측가 — <b>실전 전체 랭킹에 든 사람만</b>, 닉네임 부분일치, 순위순. 적중률이 랭킹 스냅샷에만 있어서다
 *   <li>리포트 — 제목 부분일치, 최신순
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_QUERY_LENGTH = 50;

    /** 검색 대상 타입. null 이면 전부다. */
    public enum Type {
        STOCK,
        CHANNEL,
        REPORT
    }

    private final StockService stockService;
    private final RankingRepository rankingRepository;
    private final ReportRepository reportRepository;

    public SearchResponse search(Long userId, String q, Type type, Integer limit) {
        String keyword = q == null ? "" : q.strip();
        if (keyword.isEmpty() || keyword.length() > MAX_QUERY_LENGTH) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "q");
        }
        int size = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        String escaped = escapeLike(keyword.toLowerCase(Locale.ROOT));
        String contains = "%" + escaped + "%";

        return new SearchResponse(
                wants(type, Type.STOCK) ? stockService.search(userId, contains, escaped + "%", size) : List.of(),
                wants(type, Type.CHANNEL) ? channels(contains, size) : List.of(),
                wants(type, Type.REPORT) ? reports(contains, size) : List.of());
    }

    private List<SearchResponse.Channel> channels(String contains, int size) {
        String allTime = RankingFilterKey.of(Track.REAL, null, null, null);
        return rankingRepository
                .searchByNickname(Track.REAL, allTime, contains, User.Status.ACTIVE, Limit.of(size)).stream()
                .map(hit -> new SearchResponse.Channel(hit.userId(), hit.nickname(), hit.hitRate()))
                .toList();
    }

    private List<SearchResponse.ReportItem> reports(String contains, int size) {
        return reportRepository.searchByTitle(contains, Limit.of(size)).stream()
                .map(r -> new SearchResponse.ReportItem(r.getId(), r.getTitle(), r.getUser().getNickname()))
                .toList();
    }

    private static boolean wants(Type requested, Type candidate) {
        return requested == null || requested == candidate;
    }

    /** % · _ 를 글자 그대로 찾게 한다. 쿼리들이 {@code escape '!'} 로 받는다. */
    private static String escapeLike(String keyword) {
        return keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
