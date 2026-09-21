package ssafy.a507.backend.domain.market.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.market.dto.SectorSummaryResponse;
import ssafy.a507.backend.domain.market.dto.StockListResponse;
import ssafy.a507.backend.domain.market.dto.StockPriceListResponse;
import ssafy.a507.backend.domain.market.dto.StockSummaryResponse;
import ssafy.a507.backend.domain.market.entity.Stock;
import ssafy.a507.backend.domain.market.service.StockService;

/**
 * 종목 목록·시세 — ANT-DATA-03. 명세 §1 의 {@code Base /api/v1} 을 경로에 직접 적는다.
 *
 * <p>경로가 지라 티켓의 {@code /api/stocks/{code}/quotes} 가 아니라 명세서의
 * {@code /stocks/{code}/prices} 인 이유 — 프론트 api/client.ts 와 §1 Base 가 그쪽으로 굳어
 * 있고, 경로 계약 테스트가 {@code /api/v1} 밖의 경로를 실패시킨다.
 */
@RestController
@RequestMapping("/api/v1/stocks")
@RequiredArgsConstructor
public class StockController {

    private final StockService stockService;
    private final CurrentUserProvider currentUserProvider;

    /**
     * 탐색 목록. 명세의 sentiment 는 아직 받지 않는다 — 모르는
     * 파라미터는 스프링이 무시하므로 화면은 그대로 보내도 된다. sort · perMin/perMax 는 어휘 밖
     * 값이면 400 이다(타입 변환 실패는 GlobalExceptionHandler 가 field 와 함께 400 으로 낸다).
     *
     * <p>{@code sector} 는 여러 번 올 수 있다({@code ?sector=반도체&sector=인터넷} — 그중 하나라도).
     * {@code @RequestParam List<String>} 으로 받지 않는다 — 값이 하나일 때 스프링이 쉼표로 쪼개서
     * "농업, 임업 및 어업" 같은 업종명이 둘로 갈린다. 요청의 원본 값 배열을 그대로 읽는다.
     */
    @GetMapping
    public StockListResponse list(
            HttpServletRequest request,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Stock.Market market,
            @RequestParam(required = false) BigDecimal perMin,
            @RequestParam(required = false) BigDecimal perMax,
            @RequestParam(required = false) BigDecimal pbrMin,
            @RequestParam(required = false) BigDecimal pbrMax,
            @RequestParam(required = false, defaultValue = "false") boolean watchedOnly,
            @RequestParam(required = false, defaultValue = "false") boolean hasOpenPrediction,
            @RequestParam(required = false) StockService.Sort sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return stockService.list(
                currentUserProvider.currentUserId(),
                q,
                sectors(request),
                market,
                perMin,
                perMax,
                pbrMin,
                pbrMax,
                watchedOnly,
                hasOpenPrediction,
                sort,
                cursor,
                size);
    }

    /** 빈 값은 버린다. 하나도 없으면 빈 목록 = 전체. */
    private static List<String> sectors(HttpServletRequest request) {
        String[] values = request.getParameterValues("sector");
        return values == null ? List.of() : Arrays.stream(values).filter(v -> !v.isBlank()).toList();
    }

    /** 섹터 요약 칩 — 전체 + 섹터별 종목 수·평균 등락률. */
    @GetMapping("/sectors")
    public SectorSummaryResponse sectors() {
        return stockService.sectors();
    }

    /** 요약 헤더 — 종목 상세 머리(ANT-DATA-05). 리터럴 경로 /sectors 가 이 패턴보다 먼저 잡힌다. */
    @GetMapping("/{code}")
    public StockSummaryResponse summary(@PathVariable String code) {
        return stockService.summary(currentUserProvider.currentUserId(), code);
    }

    /**
     * 실전 일봉 시계열 — 종가만. 구간을 주지 않으면 마지막 영업일까지의 최근 30일이다.
     */
    @GetMapping("/{code}/prices")
    public StockPriceListResponse prices(
            @PathVariable String code,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate to) {
        return stockService.prices(code, from, to);
    }
}
