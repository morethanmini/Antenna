package ssafy.a507.backend.domain.chain.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * PredictionEventPool 의 ABI (ANT-EVENT-02).
 *
 * <p>{@link PredictTokenAbi} 와 같은 구조·같은 이유다 — 배포 스크립트({@code contracts/scripts/emit-artifacts.js})가
 * 이 리소스를 복사하고 저장소에 커밋한다. 주소와 달리 ABI 는 없으면 부팅을 막는다: 빌드에 포함된 파일이라
 * 없거나 깨졌다면 빌드가 잘못된 것이고, 첫 배팅에서 터지는 것보다 부팅에서 터지는 편이 낫다.
 *
 * <p>여기서 이름을 고정하는 함수·이벤트는 풀 릴레이어({@code Web3jPoolRelayer})와 풀 인덱서 ③({@code PoolLogDecoder})이
 * 손으로 인코딩·디코딩할 표면이다. 컨트랙트 쪽 이름이 바뀌면 그 코드보다 여기서 먼저 깨져야 한다.
 */
@Component
@EnableConfigurationProperties(EventPoolProperties.class)
public class EventPoolAbi {

    private static final String RESOURCE_PATH = "abi/PredictionEventPool.json";

    /** 릴레이어가 부르는 상태 변경 9개 + 정산 러너(ANT-EVENT-03)가 멱등 판단에 읽는 조회 5개. */
    private static final List<String> REQUIRED_FUNCTIONS =
            List.of(
                    "openEvent", "placeBet", "seal", "reveal", "revealDone", "resolveStock", "resolveDone", "settle", "sweep",
                    "eventInfo", "getBet", "getResult", "getPool", "betCount");

    /** 인덱서 ③ 이 구독하는 이벤트 열. OZ 의 {@code RoleGranted} 류는 안 읽는다. */
    private static final List<String> REQUIRED_EVENTS =
            List.of(
                    "EventOpened", "BetPlaced", "EventSealed", "BetRevealed", "EventRevealed",
                    "StockResolved", "EventResolved", "BetSettled", "BetRefunded", "EventSettled");

    private final JsonNode abi;

    public EventPoolAbi() {
        this.abi = load();
        verifyShape();
    }

    /** ABI 배열 원본. */
    public JsonNode abi() {
        return abi;
    }

    /** 매퍼를 주입받지 않는 이유는 {@link CommitAnchorAbi} 와 같다 — Boot 4 컨텍스트마다 매퍼 빈 유무가 갈린다. */
    private static JsonNode load() {
        JsonMapper mapper = JsonMapper.builder().build();
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            JsonNode root = mapper.readTree(in);
            JsonNode node = root.get("abi");
            if (node == null || !node.isArray()) {
                throw new IllegalStateException(
                        RESOURCE_PATH + " 에 abi 배열이 없다. 배포 스크립트가 만든 파일이 맞는지 확인해라.");
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException(
                    RESOURCE_PATH + " 를 읽지 못했다. contracts 에서 배포 스크립트를 돌렸는지 확인해라.", e);
        }
    }

    private void verifyShape() {
        for (String name : REQUIRED_FUNCTIONS) {
            if (!has("function", name)) {
                throw new IllegalStateException(
                        RESOURCE_PATH + " 에 function " + name + " 이 없다. ABI 가 PredictionEventPool 의 것이 맞나?");
            }
        }
        for (String name : REQUIRED_EVENTS) {
            if (!has("event", name)) {
                throw new IllegalStateException(
                        RESOURCE_PATH + " 에 event " + name + " 이 없다. 풀 인덱서가 구독할 이벤트다.");
            }
        }
    }

    private boolean has(String type, String name) {
        for (JsonNode member : abi) {
            if (type.equals(member.path("type").asText()) && name.equals(member.path("name").asText())) {
                return true;
            }
        }
        return false;
    }
}
