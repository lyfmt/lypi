package cn.lycode.ai.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.ai.spec.LyCodeAttachmentBlock;
import cn.lycode.ai.spec.LyCodeContentBlock;
import cn.lycode.ai.spec.LyCodeGenerationOptions;
import cn.lycode.ai.spec.LyCodeMessage;
import cn.lycode.ai.spec.LyCodeModelRequest;
import cn.lycode.ai.spec.LyCodeRole;
import cn.lycode.ai.spec.LyCodeTextBlock;
import cn.lycode.ai.spec.LyCodeThinkingBlock;
import cn.lycode.ai.spec.LyCodeToolCallBlock;
import cn.lycode.ai.spec.LyCodeToolResultBlock;
import cn.lycode.ai.spec.LyCodeToolSpec;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpenAiChatCompletionsRequestBuilderTest {
    private static final String REASONING_CONTENT_COMPAT =
        "requires-reasoning-content-on-assistant-messages";

    @Test
    void buildsChatCompletionsRequestWithMessagesToolsAndReasoningEffort() {
        LyCodeToolSpec tool = new LyCodeToolSpec(
            "read_file",
            "Read a file",
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))
        );
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-1",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            "You are concise.",
            List.of(
                new LyCodeMessage(
                    LyCodeRole.USER,
                    List.of(new LyCodeTextBlock("Read pom.xml", Map.of())),
                    Map.of()
                ),
                new LyCodeMessage(
                    LyCodeRole.TOOL_RESULT,
                    List.of(new LyCodeToolResultBlock("call-1", "contents", false, Map.of())),
                    Map.of()
                )
            ),
            List.of(tool),
            new LyCodeGenerationOptions(Optional.of(256), Optional.of(0.1), Map.of()),
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
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-tool-history",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(
                new LyCodeMessage(
                    LyCodeRole.ASSISTANT,
                    List.of(new LyCodeToolCallBlock(
                        "call-1",
                        "read_file",
                        "",
                        Map.of("input", Map.of("path", "pom.xml"))
                    )),
                    Map.of()
                ),
                new LyCodeMessage(
                    LyCodeRole.TOOL_RESULT,
                    List.of(new LyCodeToolResultBlock("call-1", "contents", false, Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LyCodeGenerationOptions.defaults(),
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
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-tool-history-text",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LyCodeMessage(
                LyCodeRole.ASSISTANT,
                List.of(
                    new LyCodeTextBlock("I will read it.", Map.of()),
                    new LyCodeToolCallBlock("call-1", "read_file", "", Map.of("input", Map.of("path", "pom.xml")))
                ),
                Map.of()
            )),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.at("/messages/0/content").asText()).isEqualTo("I will read it.");
        assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
    }

    @Test
    void replaysAssistantThinkingSeparatelyWhenCompatIsEnabled() {
        LyCodeModelRequest request = assistantToolHistory(List.of(
            new LyCodeThinkingBlock("private plan", Map.of()),
            new LyCodeTextBlock("I will read it.", Map.of()),
            new LyCodeToolCallBlock("call-1", "read_file", "", Map.of("input", Map.of("path", "pom.xml")))
        ));

        for (Object enabled : List.of(true, "true")) {
            JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(
                request,
                config(Map.of(REASONING_CONTENT_COMPAT, enabled))
            );

            assertThat(body.get("messages")).hasSize(1);
            assertThat(body.at("/messages/0/role").asText()).isEqualTo("assistant");
            assertThat(body.at("/messages/0/reasoning_content").asText()).isEqualTo("private plan");
            assertThat(body.at("/messages/0/content").asText()).isEqualTo("I will read it.");
            assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
            assertThat(body.at("/messages/0/tool_calls/0/function/name").asText()).isEqualTo("read_file");
            assertThat(body.at("/messages/0/tool_calls/0/function/arguments").asText())
                .isEqualTo("{\"path\":\"pom.xml\"}");
        }
    }

    @Test
    void writesEmptyReasoningContentForAssistantToolCallWithoutThinking() {
        LyCodeModelRequest request = assistantToolHistory(List.of(
            new LyCodeToolCallBlock("call-1", "read_file", "", Map.of("input", Map.of("path", "pom.xml")))
        ));

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(
            request,
            config(Map.of(REASONING_CONTENT_COMPAT, true))
        );

        assertThat(body.at("/messages/0/reasoning_content").isTextual()).isTrue();
        assertThat(body.at("/messages/0/reasoning_content").asText()).isEmpty();
        assertThat(body.at("/messages/0/content").isNull()).isTrue();
        assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
    }

    @Test
    void keepsThinkingInVisibleContentWhenReasoningCompatIsDisabled() {
        LyCodeModelRequest request = assistantToolHistory(List.of(
            new LyCodeThinkingBlock("private plan", Map.of()),
            new LyCodeTextBlock("I will read it.", Map.of()),
            new LyCodeToolCallBlock("call-1", "read_file", "", Map.of("input", Map.of("path", "pom.xml")))
        ));

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.at("/messages/0/reasoning_content").isMissingNode()).isTrue();
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("private plan\nI will read it.");
        assertThat(body.at("/messages/0/tool_calls/0/id").asText()).isEqualTo("call-1");
    }

    @Test
    void serializesUserTextAndImageAsOneMultimodalMessage() {
        LyCodeModelRequest request = requestWithMessages(List.of(new LyCodeMessage(
            LyCodeRole.USER,
            List.of(
                new LyCodeTextBlock("describe", Map.of()),
                new LyCodeAttachmentBlock(
                    "image-1",
                    "attached image",
                    "image/png",
                    Map.of("imageUrl", "data:image/png;base64,AAA", "detail", "high")
                )
            ),
            Map.of()
        )));

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/type").asText()).isEqualTo("text");
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("describe");
        assertThat(body.at("/messages/0/content/1/type").asText()).isEqualTo("image_url");
        assertThat(body.at("/messages/0/content/1/image_url/url").asText())
            .isEqualTo("data:image/png;base64,AAA");
        assertThat(body.at("/messages/0/content/1/image_url/detail").asText()).isEqualTo("high");
    }

    @Test
    void preservesAttachmentTextWhenImageUrlIsMissing() {
        LyCodeModelRequest request = requestWithMessages(List.of(new LyCodeMessage(
            LyCodeRole.USER,
            List.of(new LyCodeAttachmentBlock("attachment-1", "image description", "image/png", Map.of())),
            Map.of()
        )));

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.at("/messages/0/content").isTextual()).isTrue();
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("image description");
    }

    @Test
    void sendsToolResultImagesAsAFollowingUserMessage() {
        LyCodeModelRequest request = requestWithMessages(List.of(new LyCodeMessage(
            LyCodeRole.TOOL_RESULT,
            List.of(
                new LyCodeToolResultBlock("call-1", "screenshot captured", false, Map.of()),
                new LyCodeAttachmentBlock(
                    "image-1",
                    "captured screenshot",
                    "image/png",
                    Map.of("imageUrl", "data:image/png;base64,AAA")
                )
            ),
            Map.of()
        )));

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(2);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("tool");
        assertThat(body.at("/messages/0/tool_call_id").asText()).isEqualTo("call-1");
        assertThat(body.at("/messages/0/content").asText()).isEqualTo("screenshot captured");
        assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/1/content/0/type").asText()).isEqualTo("image_url");
        assertThat(body.at("/messages/1/content/0/image_url/url").asText())
            .isEqualTo("data:image/png;base64,AAA");
        assertThat(body.at("/messages/1/content/0/image_url/detail").asText()).isEqualTo("high");
    }

    @Test
    void omitsBlankSystemPromptAndReasoningWhenOff() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-2",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            " ",
            List.of(),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(0);
        assertThat(body.get("reasoning_effort")).isNull();
    }

    @Test
    void includesPromptCacheKeyFromRequestMetadata() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-prompt-cache",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LyCodeMessage(LyCodeRole.USER, List.of(new LyCodeTextBlock("hello", Map.of())), Map.of())),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of("promptCacheKey", "ses_main")
        );

        JsonNode body = new OpenAiChatCompletionsRequestBuilder().build(request, config());

        assertThat(body.get("prompt_cache_key").asText()).isEqualTo("ses_main");
    }

    private static OpenAiProviderConfig config() {
        return config(Map.of());
    }

    private static OpenAiProviderConfig config(Map<String, Object> compat) {
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
            compat
        );
    }

    private static LyCodeModelRequest assistantToolHistory(List<LyCodeContentBlock> content) {
        return requestWithMessages(List.of(new LyCodeMessage(LyCodeRole.ASSISTANT, content, Map.of())));
    }

    private static LyCodeModelRequest requestWithMessages(List<LyCodeMessage> messages) {
        return new LyCodeModelRequest(
            "req-thinking-history",
            new ModelSelection("openai", "gpt-4o-mini", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            "",
            messages,
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );
    }
}
