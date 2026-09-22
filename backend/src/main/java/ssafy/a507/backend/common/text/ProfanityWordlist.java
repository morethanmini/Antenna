package ssafy.a507.backend.common.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 비속어 사전 로더 (ANT-COMMUNITY-09) — {@code resources/profanity/wordlist.json} 이 서버 쪽 단일 소스다.
 *
 * <p>단어는 원문을 리포지토리에 평문으로 남기지 않으려고 base64(UTF-8)로 보관한다. 난독화 수준의 조치다 —
 * 디코딩은 누구나 할 수 있지만 {@code grep} 과 diff 리뷰 화면에 욕설이 그대로 뜨는 것을 피하는 게 목적이다.
 * 프론트 {@code src/text/profanityWordlist.ts} 도 같은 파일에서 생성한 사본이라 두 벌이 같은 단어로 시작한다.
 *
 * <p>출처: S15P11A706(모톡) 큐레이션 211 단어 — korcen(Apache-2.0) 발췌. 검사는 정규화 후
 * <b>부분 문자열 일치</b>라 짧거나 일상어와 겹치는 항목을 넣으면 정상 문장이 거절된다. 추가할 때 헤더의 note 를 읽는다.
 *
 * <p>Boot 4 = Jackson 3. 웹 계층 매퍼 빈에 기대지 않고 여기서 만든다({@code CommitAnchorAbi} 와 같은 이유).
 */
@Component
public class ProfanityWordlist {

    private static final String RESOURCE_PATH = "profanity/wordlist.json";

    private final List<String> decodedWords;

    public ProfanityWordlist() {
        JsonNode root;
        try (InputStream in = new ClassPathResource(RESOURCE_PATH).getInputStream()) {
            root = JsonMapper.builder().build().readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("비속어 사전 로드 실패: " + RESOURCE_PATH, e);
        }
        List<String> words = new ArrayList<>();
        for (JsonNode node : root.path("words")) {
            words.add(new String(Base64.getDecoder().decode(node.asString()), StandardCharsets.UTF_8));
        }
        this.decodedWords = List.copyOf(words);
    }

    /** base64 디코딩된 원문 단어들. */
    public List<String> decodedWords() {
        return decodedWords;
    }
}
