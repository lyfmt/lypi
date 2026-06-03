package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.spec.LypiGenerationOptions;
import cn.lypi.ai.spec.LypiMessage;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiRole;
import cn.lypi.ai.spec.LypiTextBlock;
import cn.lypi.ai.spec.LypiToolSpec;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenAiResponsesRequestBuilderTest {
    @Test
    void buildsResponsesRequestWithMessagesToolsThinkingAndOutputBudget() {
        LypiToolSpec tool = new LypiToolSpec(
            "math_operation",
            "Perform basic arithmetic",
            Map.of(
                "type", "object",
                "properties", Map.of("a", Map.of("type", "number")),
                "required", List.of("a")
            )
        );
        LypiModelRequest request = new LypiModelRequest(
            "req-1",
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            "You are concise.",
            List.of(new LypiMessage(
                LypiRole.USER,
                List.of(new LypiTextBlock("What is 2+2?", Map.of())),
                Map.of("messageId", "msg-1")
            )),
            List.of(tool),
            new LypiGenerationOptions(Optional.of(512), Optional.of(0.2), Map.of("traceId", "trace-1")),
            Map.of()
        );
        OpenAiProviderConfig config = config();

        JsonNode body = new OpenAiResponsesRequestBuilder().build(request, config);

        assertThat(body.get("model").asText()).isEqualTo("gpt-5-mini");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("instructions").asText()).isEqualTo("You are concise.");
        assertThat(body.at("/input/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/input/0/content/0/type").asText()).isEqualTo("input_text");
        assertThat(body.at("/input/0/content/0/text").asText()).isEqualTo("What is 2+2?");
        assertThat(body.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(body.at("/tools/0/name").asText()).isEqualTo("math_operation");
        assertThat(body.at("/tools/0/parameters/properties/a/type").asText()).isEqualTo("number");
        assertThat(body.at("/reasoning/effort").asText()).isEqualTo("high");
        assertThat(body.get("max_output_tokens").asInt()).isEqualTo(512);
        assertThat(body.get("api_key")).isNull();
    }

    @Test
    void omitsReasoningWhenThinkingIsOff() {
        LypiModelRequest request = new LypiModelRequest(
            "req-2",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiResponsesRequestBuilder().build(request, config());

        assertThat(body.get("reasoning")).isNull();
    }

    private static OpenAiProviderConfig config() {
        return new OpenAiProviderConfig(
            "openai",
            URI.create("https://api.openai.com/v1"),
            Optional.empty(),
            "/v1/responses",
            "test-key",
            RequestStyle.RESPONSES,
            RequestStyle.CHAT_COMPLETIONS,
            TransportMode.AUTO,
            Duration.ofSeconds(30),
            1,
            Map.of()
        );
    }
}
