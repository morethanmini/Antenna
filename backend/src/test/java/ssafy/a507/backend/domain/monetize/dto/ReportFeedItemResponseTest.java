package ssafy.a507.backend.domain.monetize.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 카드 표지·발췌를 본문에서 뽑는 규칙. 마크다운을 읽는 유일한 자리라 여기만 본다.
 *
 * <p>잠금 분기는 {@code of()} 의 삼항 한 줄이라 따로 두지 않는다 — 규칙은 "본문이 없으면 둘 다
 * null" 이고, 그 입력(=null)은 아래 테스트가 직접 확인한다.
 */
class ReportFeedItemResponseTest {

    @Test
    @DisplayName("본문 첫 이미지를 표지로 뽑는다 — 뒤에 오는 이미지는 무시한다")
    void firstImageWins() {
        String body = "앞글\n![차트](https://cdn.example/a.png)\n뒷글\n![두번째](https://cdn.example/b.png)";
        assertThat(ReportFeedItemResponse.firstImage(body)).isEqualTo("https://cdn.example/a.png");
    }

    @Test
    @DisplayName("이미지 title 이 붙어 있어도 주소만 뽑는다")
    void firstImageWithTitle() {
        assertThat(ReportFeedItemResponse.firstImage("![alt](https://cdn.example/a.png \"제목\")"))
                .isEqualTo("https://cdn.example/a.png");
    }

    @Test
    @DisplayName("이미지가 없거나 본문이 잠겨 있으면(null) 표지도 없다")
    void noImage() {
        assertThat(ReportFeedItemResponse.firstImage("그림 없는 본문")).isNull();
        assertThat(ReportFeedItemResponse.firstImage(null)).isNull();
    }

    @Test
    @DisplayName("발췌는 마크다운 기호를 걷어낸 평문이다 — 이미지 alt 가 남지 않는다")
    void summaryIsPlainText() {
        String body = "# 제목\n\n![차트](https://cdn.example/a.png)\n\n- **삼성전기**가 _반등_ 했다";
        assertThat(ReportFeedItemResponse.summarize(body)).isEqualTo("제목 삼성전기가 반등 했다");
    }

    @Test
    @DisplayName("링크는 글자만 남긴다 — 주소가 끼면 발췌를 읽을 수 없다")
    void linkKeepsTextOnly() {
        assertThat(ReportFeedItemResponse.summarize("근거는 [공시](https://dart.fss.or.kr/x) 에 있다"))
                .isEqualTo("근거는 공시 에 있다");
    }

    @Test
    @DisplayName("120자에서 자르고 말줄임표를 붙인다")
    void summaryIsCut() {
        String body = "가".repeat(200);
        String summary = ReportFeedItemResponse.summarize(body);
        assertThat(summary).hasSize(121).endsWith("…");
        assertThat(summary).startsWith("가".repeat(120));
    }

    @Test
    @DisplayName("잠긴 본문(null)과 기호뿐인 본문은 발췌가 없다")
    void noSummary() {
        assertThat(ReportFeedItemResponse.summarize(null)).isNull();
        assertThat(ReportFeedItemResponse.summarize("![표지](https://cdn.example/a.png)")).isNull();
    }
}
