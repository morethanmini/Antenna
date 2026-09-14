package ssafy.a507.backend.domain.season.repository;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.season.entity.SeasonNews;

public interface SeasonNewsRepository extends JpaRepository<SeasonNews, Long> {

    long countBySeason_Id(Long seasonId);

    /** 시즌의 뉴스 전부. 시즌을 지울 때 종목보다 먼저 지운다(FK). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from SeasonNews n where n.season.id = :seasonId")
    void deleteBySeasonId(@Param("seasonId") Long seasonId);

    /** 시장 전체 행(종목 없음 — AI 요약). 최근 게임일부터. 상한은 호출부가 진행일로 건다. */
    List<SeasonNews> findBySeason_IdAndTickerIsNullAndGameDayLessThanEqualOrderByGameDayDescIdAsc(
            Long seasonId, int uptoDay, Limit limit);

    /** 한 종목의 행. 최근 게임일부터. 상한은 호출부가 진행일로 건다. */
    List<SeasonNews> findBySeason_IdAndTicker_IdAndGameDayLessThanEqualOrderByGameDayDescIdAsc(
            Long seasonId, Long tickerId, int uptoDay, Limit limit);
}
