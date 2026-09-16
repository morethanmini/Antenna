package ssafy.a507.backend.domain.event.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ssafy.a507.backend.domain.event.dto.EventEntryCreateRequest;
import ssafy.a507.backend.domain.prediction.entity.Prediction;

/**
 * 묶음 등록 서명 문자열의 고정 벡터 — ANT-EVENT-05 (작업명세 v1.2 §6 · §9).
 *
 * <p><b>왜 파일로 두나</b> — 서명은 암호화가 아니라 복원이다. 프론트가 만든 문자열이 서버가 조립한
 * 것과 한 바이트라도 다르면 예외가 아니라 <b>엉뚱한 주소</b>가 나와 401 이 된다. 접수가 금요일
 * 13:30 이라 그날 처음 맞춰 보면 고칠 시간이 없다. 이 파일이 양쪽이 같은 표를 보는 자리다
 * ({@code commit/commit-cross-fixture.json} 과 같은 역할).
 *
 * <p>스프링 컨텍스트를 띄우지 않는다 — 문자열 조립에 DB 도 체인도 필요 없다.
 */
@DisplayName("묶음 등록 서명 고정 벡터")
class EventEntrySignatureFixtureTest {

    private static final String FIXTURE = "/event/event-entry-signature-fixture.json";
    /** 형식 검사만 통과하면 되는 값 — 이 테스트가 붙드는 건 줄 조립이다. */
    private static final String SALT =
            "dc7591f85642df187ccd193f6ba8c3f5bfac5de83375d6fb88d712ab489fa491";

    @Test
    @DisplayName("서버가 조립하는 문자열이 고정 벡터와 바이트 단위로 같다")
    void 고정_벡터와_같다() throws Exception {
        JsonNode fixture = load();
        for (JsonNode testCase : fixture.get("cases")) {
            EventEntryService.Signed signed =
                    EventEntryService.Signed.of(
                            testCase.get("eventId").asLong(), itemsOf(testCase), "0x00");

            String payload =
                    signed.signingPayload(
                            testCase.get("nonce").asText(), testCase.get("chainId").asLong());

            assertThat(payload)
                    .as(testCase.get("name").asText())
                    .isEqualTo(testCase.get("payload").asText());
        }
    }

    @Test
    @DisplayName("목표가는 늘 소수 둘째 자리다 — 93000 과 93000.00 이 갈리면 서명만 어긋난다")
    void 목표가_표기() {
        EventEntryService.Signed signed =
                EventEntryService.Signed.of(
                        1L,
                        List.of(item("005930", Prediction.Direction.UP, "93000", 1000, "근거")),
                        "0x00");

        assertThat(signed.signingPayload("n", 1L)).contains("/93000.00/");
    }

    @Test
    @DisplayName("줄 순서는 요청의 items 순서 그대로다 — 서버가 정렬하면 프론트와 갈린다")
    void 줄_순서() {
        EventEntryService.Signed signed =
                EventEntryService.Signed.of(
                        1L,
                        List.of(
                                item("000660", Prediction.Direction.UP, "1.00", 1, "가"),
                                item("005930", Prediction.Direction.UP, "1.00", 1, "나")),
                        "0x00");

        String payload = signed.signingPayload("n", 1L);
        assertThat(payload.indexOf("000660")).isLessThan(payload.indexOf("005930"));
    }

    @Test
    @DisplayName("근거가 비어 있어도 noteHash 는 난수다 — salt 가 들어가기 때문이다")
    void 빈_근거도_해시가_있다() {
        EventEntryService.Signed empty =
                EventEntryService.Signed.of(
                        1L, List.of(item("005930", Prediction.Direction.UP, "1.00", 1, "")), "0x00");
        EventEntryService.Signed written =
                EventEntryService.Signed.of(
                        1L, List.of(item("005930", Prediction.Direction.UP, "1.00", 1, "근거")), "0x00");

        assertThat(empty.signingPayload("n", 1L)).isNotEqualTo(written.signingPayload("n", 1L));
    }

    private static List<EventEntryCreateRequest.Item> itemsOf(JsonNode testCase) {
        List<EventEntryCreateRequest.Item> items = new ArrayList<>();
        for (JsonNode node : testCase.get("items")) {
            items.add(new EventEntryCreateRequest.Item(
                    node.get("stockCode").asText(),
                    Prediction.Direction.valueOf(node.get("direction").asText()),
                    new BigDecimal(node.get("targetPrice").asText()),
                    BigInteger.valueOf(node.get("stake").asLong()),
                    node.get("note").asText(),
                    node.get("noteSalt").asText(),
                    List.of()));
        }
        return items;
    }

    private static EventEntryCreateRequest.Item item(
            String code, Prediction.Direction direction, String target, long stake, String note) {
        return new EventEntryCreateRequest.Item(
                code,
                direction,
                new BigDecimal(target),
                BigInteger.valueOf(stake),
                note,
                SALT,
                List.of());
    }

    private static JsonNode load() throws Exception {
        try (InputStream in = EventEntrySignatureFixtureTest.class.getResourceAsStream(FIXTURE)) {
            return new ObjectMapper().readTree(in);
        }
    }
}
