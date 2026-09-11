package ssafy.a507.backend.domain.market.repository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import ssafy.a507.backend.domain.market.entity.Stock;

/**
 * 종목 마스터. 탐색 목록은 필터 조합이 많아 파생 쿼리 대신 {@link JpaSpecificationExecutor} 로
 * 조건을 조립한다 — 커서는 종목코드다. 불변 자연키라 페이지 사이에 값이 바뀌지 않고, 정렬 키와
 * 커서가 같아 건너뛰거나 겹치는 행이 생기지 않는다.
 */
public interface StockRepository extends JpaRepository<Stock, String>, JpaSpecificationExecutor<Stock> {

    /** 상장 종목 전부 — 섹터 요약 재료. 300개 안팎이라 한 번에 읽는다. */
    List<Stock> findByListedTrue();

    /** 상장주식수를 한 종목이라도 받았는가 — 칸이 생긴 배포의 첫 부팅 판정. */
    boolean existsByListedSharesIsNotNull();

    /**
     * 통합 검색의 종목 후보 (ANT-DATA-05) — 종목명·섹터 부분일치 또는 종목코드 앞자리. 상장 종목 중
     * activeSince 이후 시세가 한 점이라도 있는 것만 — 탐색 목록과 같은 활성 판정이다. 순서는 부르는 쪽이 잡는다.
     *
     * @param contains 소문자 · LIKE 이스케이프('!')를 마친 {@code %q%}
     * @param prefix 소문자 · LIKE 이스케이프('!')를 마친 {@code q%}
     */
    @Query("""
            select s from Stock s
            where s.listed = true
              and (lower(s.name) like :contains escape '!'
                   or lower(s.sector) like :contains escape '!'
                   or lower(s.code) like :prefix escape '!')
              and s.code in (select q.stock.code from DailyQuote q where q.tradeDate >= :activeSince)
            """)
    List<Stock> search(
            @Param("contains") String contains,
            @Param("prefix") String prefix,
            @Param("activeSince") LocalDate activeSince);
}
