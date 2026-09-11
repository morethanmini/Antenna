package ssafy.a507.backend.domain.search.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ssafy.a507.backend.common.security.CurrentUserProvider;
import ssafy.a507.backend.domain.search.dto.SearchResponse;
import ssafy.a507.backend.domain.search.service.SearchService;

/**
 * 통합 검색 — ANT-DATA-05, 화면 A-03. q 가 없으면 400, type 이 어휘 밖이면 스프링의 enum 변환이 실패해 400 이다.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public SearchResponse search(
            @RequestParam String q,
            @RequestParam(required = false) SearchService.Type type,
            @RequestParam(required = false) Integer limit) {
        return searchService.search(currentUserProvider.currentUserId(), q, type, limit);
    }
}
