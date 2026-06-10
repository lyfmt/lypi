package cn.lypi.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.spec.LypiGenerationOptions;
import cn.lypi.ai.spec.LypiMessage;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiRole;
import cn.lypi.ai.spec.LypiTextBlock;
import cn.lypi.ai.spec.LypiToolCallBlock;
import cn.lypi.ai.spec.LypiToolResultBlock;
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

class OpenAiChatCompletionsRequestBuilderTest {
    @Test
    void buildsChatCompletionsRequestWithMessagesToolsAndReasoningEffort() {
        LypiToolSpec tool = new LypiToolSpec(
            "read_file",
            "Read a file",
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))
        );
        LypiModelRequest request = new LypiModelRequest(
            "req-1",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            "You are concise.",
            List.of(
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("Read pom.xml", Map.of())),
                    Map.of()
                ),
                new LypiMessage(
                    LypiRole.TOOL_RESULT,
                    List.of(new LypiToolResultBlock("call-1", "contents", false, Map.of())),
                    Map.of()
                )
            ),
            List.of(tool),
            new LypiGenerationOptions(Optional.of(256), Optional.of(0.1), Map.of()),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("You are concise.");
        assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/1/content").asText()).isEqualTo("Read pom.xml");
        assertThat(body.at("/messages/2/role").asText()).isEqualTo("tool");
        assertThat(body.at("/messages/2/tool_call_id").asText()).isEqualTo("call-1");
        assertThat(body.at("/tools/0/type").asText()).isEqualTo("function");
        assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("read_file");
        assertThat(body.at("/tools/0/function/parameters/properties/path/type").asText()).isEqualTo("string");
        assertThat(body.get("reasoning_effort").asText()).isEqualTo("medium");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.get("api_key")).isNull();
    }

    @Test
    void serializesAssistantToolCallHistoryAsToolCalls() {
        LypiModelRequest request = new LypiModelRequest(
            "req-tool-history",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(
                new LypiMessage(
                    LypiRole.ASSISTANT,
                    List.of(new LypiToolCallBlock(
                        "call-1",
                        "read_file",
                        "",
                        Map.of("input", Map.of("path", "pom.xml"))
                    )),
                    Map.of()
                ),
                new LypiMessage(
                    LypiRole.TOOL_RESULT,
                    List.of(new LypiToolResultBlock("call-1", "contents", false, Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.at("/messages/0/role").asText()).isEqualTo("assistant");
        assertThat(body.at("/messages/0/content").isNull()).isTrue();
        assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
        assertThat(body.at("/messages/0/tool_calls/0/type").asText()).isEqualTo("function");
        assertThat(body.at("/messages/0/tool_calls/0/function/name").asText()).isEqualTo("read_file");
        assertThat(body.at("/messages/0/tool_calls/0/function/arguments").asText()).isEqualTo("{\"path\":\"pom.xml\"}");
        assertThat(body.at("/messages/1/role").asText()).isEqualTo("tool");
        assertThat(body.at("/messages/1/tool_call_id").asText()).isEqualTo("call-1");
    }

    @Test
    void preservesAssistantContentWhenToolCallHistoryAlsoHasText() {
        LypiModelRequest request = new LypiModelRequest(
            "req-tool-history-text",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LypiMessage(
                LypiRole.ASSISTANT,
                List.of(
                    new LypiTextBlock("I will read it.", Map.of()),
                    new LypiToolCallBlock("call-1", "read_file", "", Map.of("input", Map.of("path", "pom.xml")))
                ),
                Map.of()
            )),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.at("/messages/0/content").asText()).isEqualTo("I will read it.");
        assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
    }

    @Test
    void omitsBlankSystemPromptAndReasoningWhenOff() {
        LypiModelRequest request = new LypiModelRequest(
            "req-2",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            " ",
            List.of(),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(0);
        assertThat(body.get("reasoning_effort")).isNull();
    }

    @Test
    void includesPromptCacheKeyFromRequestMetadata() {
        LypiModelRequest request = new LypiModelRequest(
            "req-prompt-cache",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LypiMessage(LypiRole.USER, List.of(new LypiTextBlock("hello", Map.of())), Map.of())),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of("promptCacheKey", "ses_main")
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("prompt_cache_key").asText()).isEqualTo("ses_main");
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
