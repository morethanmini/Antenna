package ssafy.a507.backend.domain.season.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ssafy.a507.backend.domain.season.service.SeasonMarketDigest.Quote;

/**
 * 복기 재료의 시장 쪽(ANT-SEASON-09). 6게임일 · 세 종목.
 *
 * <pre>
 *   A(은행) 100 100  90  90 110 120  → +20%
 *   B(은행) 100 100  95  95 100 110  → +10%
 *   C(건설) 100 100  80  80  80  70  → −30%
 *   등가중  1.0 1.0 .8833 .8833 .9667 1.0
 * </pre>
 */
class SeasonMarketDigestTest {

    private static final int[] A = {100, 100, 90, 90, 110, 120};
    private static final int[] B = {100, 100, 95, 95, 100, 110};
    private static final int[] C = {100, 100, 80, 80, 80, 70};

    private static SeasonMarketDigest digest() {
        List<Quote> quotes = new ArrayList<>();
        add(quotes, 1L, "A", "은행", A);
        add(quotes, 2L, "B", "은행", B);
        add(quotes, 3L, "C", "건설", C);
        return SeasonMarketDigest.of(quotes, 6);
    }

    private static void add(List<Quote> quotes, long id, String name, String sector, int[] closes) {
        for (int i = 0; i < closes.length; i++) {
            quotes.add(new Quote(id, name, sector, i + 1, BigDecimal.valueOf(closes[i])));
        }
    }

    @Test
    @DisplayName("구간을 셋으로 나눠 등가중 등락을 적는다")
    void 구간별_등락() {
        assertThat(digest().text())
                .contains("D+1~D+2 +0.0%")
                .contains("D+3~D+4 -11.7%")
                .contains("D+5~D+6 +13.2%");
    }

    @Test
    @DisplayName("크게 빠진 날과 크게 오른 날을 게임일로 짚는다 — 내린 날만 급락, 오른 날만 급등")
    void 큰_움직임() {
        SeasonMarketDigest d = digest();
        assertThat(d.text()).contains("급락 D+3 -11.7%").contains("급등 D+5 +9.4%, D+6 +3.4%");
        assertThat(d.keyDays()).containsExactly(3, 5, 6);
    }

    @Test
    @DisplayName("업종과 종목의 첫날 대비 마지막 날 등락 — 오른 쪽과 내린 쪽을 가른다")
    void 업종과_종목() {
        assertThat(digest().text())
                .contains("강세 은행 +15.0%")
                .contains("약세 건설 -30.0%")
                .contains("많이 오른 A(은행) +20.0%, B(은행) +10.0%")
                .contains("많이 내린 C(건설) -30.0%");
    }

    @Test
    @DisplayName("체결가 대비 마지막 날 종가 등락 — 모르는 종목은 null")
    void 매매_이후() {
        SeasonMarketDigest d = digest();
        assertThat(d.sinceTrade(1L, BigDecimal.valueOf(100))).isEqualByComparingTo("20.0");
        assertThat(d.sinceTrade(3L, BigDecimal.valueOf(80))).isEqualByComparingTo("-12.5");
        assertThat(d.sinceTrade(99L, BigDecimal.valueOf(100))).isNull();
    }

    @Test
    @DisplayName("가격이 없으면 빈 재료다 — 복기 자체를 막지 않는다")
    void 가격_없음() {
        SeasonMarketDigest d = SeasonMarketDigest.of(List.of(), 6);
        assertThat(d.text()).isEmpty();
        assertThat(d.keyDays()).isEmpty();
    }
}
