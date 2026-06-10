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

    @Test
    void buildsWebSocketResponseCreateEnvelopeWithoutHttpStreamFlag() {
        LypiModelRequest request = new LypiModelRequest(
            "req-3",
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "You are concise.",
            List.of(new LypiMessage(LypiRole.USER, List.of(new LypiTextBlock("hello", Map.of())), Map.of())),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode envelope = new OpenAiResponsesRequestBuilder().buildWebSocketCreateEvent(request, config());

        assertThat(envelope.get("type").asText()).isEqualTo("response.create");
        assertThat(envelope.get("stream")).isNull();
        assertThat(envelope.at("/response/model").asText()).isEqualTo("gpt-5-mini");
        assertThat(envelope.at("/response/instructions").asText()).isEqualTo("You are concise.");
        assertThat(envelope.at("/response/input/0/content/0/text").asText()).isEqualTo("hello");
    }

    @Test
    void usesPreviousResponseIdAndOnlySendsMessagesAfterCachedAssistantPrefix() {
        LypiModelRequest request = new LypiModelRequest(
            "req-cache",
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "You are concise.",
            List.of(
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("first", Map.of())),
                    Map.of("messageId", "msg-user-1")
                ),
                new LypiMessage(
                    LypiRole.ASSISTANT,
                    List.of(new LypiTextBlock("answer", Map.of())),
                    Map.of(
                        "messageId", "msg-assistant-1",
                        "providerConversationState", Map.of(
                            "provider", "openai",
                            "style", "responses",
                            "previousResponseId", "resp-123"
                        )
                    )
                ),
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("follow up", Map.of())),
                    Map.of("messageId", "msg-user-2")
                )
            ),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of("providerConversationState", Map.of(
                "provider", "openai",
                "style", "responses",
                "previousResponseId", "resp-123",
                "messageId", "msg-assistant-1"
            ))
        );

        JsonNode body = new OpenAiResponsesRequestBuilder().build(request, config());

        assertThat(body.get("previous_response_id").asText()).isEqualTo("resp-123");
        assertThat(body.get("input")).hasSize(1);
        assertThat(body.at("/input/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/input/0/content/0/text").asText()).isEqualTo("follow up");
    }

    @Test
    void keepsFullInputWithoutPreviousResponseIdWhenToolResultsFollowCachedAssistant() {
        LypiModelRequest request = new LypiModelRequest(
            "req-tool-cache",
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "You are concise.",
            List.of(
                new LypiMessage(
                    LypiRole.ASSISTANT,
                    List.of(new LypiTextBlock("tool call", Map.of())),
                    Map.of(
                        "messageId", "msg-assistant-1",
                        "providerConversationState", Map.of(
                            "provider", "openai",
                            "style", "responses",
                            "previousResponseId", "resp-123"
                        )
                    )
                ),
                new LypiMessage(
                    LypiRole.TOOL_RESULT,
                    List.of(new LypiTextBlock("tool output", Map.of())),
                    Map.of("messageId", "msg-tool-1")
                )
            ),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of("providerConversationState", Map.of(
                "provider", "openai",
                "style", "responses",
                "previousResponseId", "resp-123",
                "messageId", "msg-assistant-1"
            ))
        );

        JsonNode body = new OpenAiResponsesRequestBuilder().build(request, config());

        assertThat(body.get("previous_response_id")).isNull();
        assertThat(body.get("input")).hasSize(2);
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
