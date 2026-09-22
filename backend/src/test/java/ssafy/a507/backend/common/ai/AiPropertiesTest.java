package ssafy.a507.backend.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * {@code .env} 값이 설정으로 묶이는 모양을 고정한다. 둘 다 2026-09-23 OpenAI 직접 호출로 옮기며
 * 실제로 겪은 일이다 — 비운 온도가 빈 문자열로 넘어와 Double 바인딩이 깨지면 기동 자체가 안 되고,
 * 모델 ID 뒤 공백 하나는 "invalid model ID" 400 으로 돌아온다.
 */
@DisplayName("AI 설정 바인딩")
class AiPropertiesTest {

    private static AiProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("app.ai", Bindable.of(AiProperties.class))
                .get();
    }

    @Test
    @DisplayName("온도를 비우면(${AI_TEMPERATURE:}) null 로 묶인다 — 요청에 싣지 않는 신호")
    void 빈_온도는_null() {
        AiProperties p = bind(Map.of("app.ai.api-key", "k", "app.ai.temperature", ""));

        assertThat(p.temperature()).isNull();
    }

    @Test
    @DisplayName("온도를 적으면 숫자로 묶인다")
    void 적은_온도는_숫자() {
        AiProperties p = bind(Map.of("app.ai.api-key", "k", "app.ai.temperature", "0"));

        assertThat(p.temperature()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("값 뒤 공백·캐리지리턴은 잘라 낸다 — 윈도우에서 .env 를 편집하면 붙는다")
    void 뒤_공백은_잘라_낸다() {
        AiProperties p = bind(Map.of(
                "app.ai.api-key", "k\r",
                "app.ai.base-url", "https://api.openai.com/v1/ ",
                "app.ai.model", "gpt-5.6-luna   "));

        assertThat(p.apiKey()).isEqualTo("k");
        assertThat(p.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(p.model()).isEqualTo("gpt-5.6-luna");
    }
}
