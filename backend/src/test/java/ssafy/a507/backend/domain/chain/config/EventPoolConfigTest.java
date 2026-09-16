package ssafy.a507.backend.domain.chain.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;

/**
 * ANT-EVENT-02 — 풀 배포 산출물(주소·ABI)이 서버 설정으로 들어오는지.
 *
 * <p>테스트 프로파일은 주소를 비워 둔다. 그 상태로 컨텍스트가 뜨는 것 자체가 검증 대상이다 — 풀을 배포하지 않은 팀원의 로컬이
 * 부팅에 실패하면 안 된다. ABI 표면 검사는 릴레이어·인덱서 ③ 이 손으로 인코딩·디코딩할 시그니처를 못박는다.
 */
@SpringBootTest
@DisplayName("PredictionEventPool 설정 주입")
class EventPoolConfigTest {

    @Autowired EventPoolProperties properties;
    @Autowired EventPoolAbi abi;

    @Test
    @DisplayName("주소가_비어_있어도_컨텍스트가_뜬다")
    void 주소가_비어_있어도_컨텍스트가_뜬다() {
        assertThat(properties).isNotNull();
        assertThat(properties.isDeployed()).isFalse();
        assertThat(properties.normalizedAddress()).isNull();
    }

    @Test
    @DisplayName("잘린_주소는_배포_안_됨으로_본다")
    void 잘린_주소는_배포_안_됨으로_본다() {
        assertThat(new EventPoolProperties("0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e", null).isDeployed()).isFalse();
        assertThat(new EventPoolProperties("0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0", null).isDeployed()).isTrue();
        assertThat(new EventPoolProperties("0x9fE46736679d2D9a65F0992F2272dE9f3c7fa6e0", null).normalizedAddress())
                .isEqualTo("0x9fe46736679d2d9a65f0992f2272de9f3c7fa6e0");
    }

    @Test
    @DisplayName("ABI에_상태변경_9개_조회_5개_이벤트_10개_에러_16개가_있다")
    void ABI_표면() {
        assertThat(memberNames("function"))
                .contains("openEvent", "placeBet", "seal", "reveal", "revealDone", "resolveStock", "resolveDone", "settle", "sweep",
                        "eventInfo", "getBet", "getResult", "getPool", "betCount", "previewPayout", "hasBet", "token", "treasury");
        assertThat(memberNames("event"))
                .contains("EventOpened", "BetPlaced", "EventSealed", "BetRevealed", "EventRevealed",
                        "StockResolved", "EventResolved", "BetSettled", "BetRefunded", "EventSettled");
        assertThat(memberNames("error"))
                .contains("BadState", "ZeroAddress", "BadConfig", "WrongStock", "StakeOutOfRange", "AlreadyBet", "NoSuchBet",
                        "AlreadyRevealed", "BadCommit", "LengthMismatch", "AlreadyResolved", "NotAllResolved", "AlreadySettled", "NotAllSettled",
                        "AccessControlUnauthorizedAccount");
        // ERC20InsufficientBalance 는 토큰의 에러라 풀 ABI 에 없다 — 릴레이어가 셀렉터를 직접 든다.
        assertThat(memberNames("error")).doesNotContain("ERC20InsufficientBalance");
    }

    @Test
    @DisplayName("placeBet_은_eventId_user_stockIdx_stake_commit_5인자고_uint16_bytes32_타입이다")
    void placeBet_인자() {
        JsonNode inputs = member("function", "placeBet").get("inputs");
        assertThat(inputs).hasSize(5);
        assertThat(inputs.get(0).path("type").asText()).isEqualTo("uint256");
        assertThat(inputs.get(1).path("type").asText()).isEqualTo("address");
        assertThat(inputs.get(2).path("type").asText()).isEqualTo("uint16");
        assertThat(inputs.get(3).path("type").asText()).isEqualTo("uint256");
        assertThat(inputs.get(4).path("type").asText()).isEqualTo("bytes32");
    }

    @Test
    @DisplayName("reveal_은_uint256_배열_uint8_배열_bytes32_배열을_받는다")
    void reveal_인자() {
        JsonNode inputs = member("function", "reveal").get("inputs");
        assertThat(inputs).hasSize(4);
        assertThat(inputs.get(1).path("type").asText()).isEqualTo("uint256[]");
        assertThat(inputs.get(2).path("type").asText()).isEqualTo("uint8[]");
        assertThat(inputs.get(3).path("type").asText()).isEqualTo("bytes32[]");
    }

    @Test
    @DisplayName("이벤트_indexed_모양 — BetPlaced 는 eventId·betId·user 가 indexed, StockResolved 는 eventId·stockIdx")
    void 이벤트_모양() {
        JsonNode placed = member("event", "BetPlaced").get("inputs");
        assertThat(placed).hasSize(6);
        assertThat(placed.get(0).path("indexed").asBoolean()).isTrue();
        assertThat(placed.get(1).path("indexed").asBoolean()).isTrue();
        assertThat(placed.get(2).path("indexed").asBoolean()).isTrue();
        assertThat(placed.get(3).path("type").asText()).isEqualTo("uint16");
        assertThat(placed.get(5).path("type").asText()).isEqualTo("bytes32");

        JsonNode resolved = member("event", "StockResolved").get("inputs");
        assertThat(resolved).hasSize(5);
        assertThat(resolved.get(1).path("name").asText()).isEqualTo("stockIdx");
        assertThat(resolved.get(1).path("indexed").asBoolean()).isTrue();
        assertThat(resolved.get(4).path("name").asText()).isEqualTo("winner");
        assertThat(resolved.get(4).path("type").asText()).isEqualTo("uint8");
    }

    @Test
    @DisplayName("eventInfo_는_view_고_struct_멤버_8개가_전부_정적_타입이다 — 워드 슬라이스 디코딩의 전제")
    void eventInfo_struct() {
        JsonNode fn = member("function", "eventInfo");
        assertThat(fn.path("stateMutability").asText()).isEqualTo("view");
        JsonNode components = fn.get("outputs").get(0).get("components");
        assertThat(components).hasSize(8);
        List<String> names = new ArrayList<>();
        for (JsonNode c : components) {
            names.add(c.path("name").asText());
            assertThat(c.path("type").asText()).startsWith("uint");
        }
        assertThat(names).containsExactly("status", "stockCount", "resolvedCount", "minStake", "maxStake", "betCount", "settledCount", "escrow");

        JsonNode bet = member("function", "getBet").get("outputs").get(0).get("components");
        List<String> betNames = new ArrayList<>();
        for (JsonNode c : bet) {
            betNames.add(c.path("name").asText());
        }
        assertThat(betNames).containsExactly("user", "stockIdx", "revealed", "settled", "side", "stake", "commit", "payout");
    }

    private List<String> memberNames(String type) {
        List<String> names = new ArrayList<>();
        for (JsonNode node : abi.abi()) {
            if (type.equals(node.path("type").asText())) {
                names.add(node.path("name").asText());
            }
        }
        return names;
    }

    private JsonNode member(String type, String name) {
        for (JsonNode node : abi.abi()) {
            if (type.equals(node.path("type").asText()) && name.equals(node.path("name").asText())) {
                return node;
            }
        }
        return null;
    }
}
