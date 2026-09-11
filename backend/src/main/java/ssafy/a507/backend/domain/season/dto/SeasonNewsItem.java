package ssafy.a507.backend.domain.season.dto;

import ssafy.a507.backend.domain.season.entity.SeasonNews;

/**
 * 시즌 뉴스 한 건.
 *
 * <p><b>실제 날짜·언론사가 없다.</b> 제목·발췌에서 지웠고 {@code gameDay} 만 있다. {@code link} 는 원문으로
 * 가는 주소라 누르면 날짜가 보이지만, 연습 모드만 남아 그대로 싣는다(2026-09-11).
 *
 * <p>{@code AI_HINT} 는 그 게임일의 시장 전체 요약이다 — {@code title} 이 한 줄 요약, {@code body} 가 주요
 * 사건 세 줄(줄바꿈으로 가른다), {@code link} 는 null 이다.
 */
public record SeasonNewsItem(SeasonNews.Kind kind, String title, String body, String link, int gameDay) {

    public static SeasonNewsItem from(SeasonNews news) {
        return new SeasonNewsItem(news.getKind(), news.getTitle(), news.getBody(), news.getLink(), news.getGameDay());
    }
}
