package cn.lycode.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.ai.spec.LyCodeAttachmentBlock;
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

class AnthropicMessagesRequestBuilderTest {
    @Test
    void buildsMessagesRequestWithSystemToolsToolUseAndToolResult() {
        LyCodeToolSpec tool = new LyCodeToolSpec(
            "read_file",
            "Read a local file.",
            Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")
            )
        );
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-1",
            new ModelSelection("anthropic", "claude-sonnet-4-5", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            "You are concise.",
            List.of(
                new LyCodeMessage(
                    LyCodeRole.USER,
                    List.of(new LyCodeTextBlock("Inspect pom.xml", Map.of())),
                    Map.of("messageId", "msg-1")
                ),
                new LyCodeMessage(
                    LyCodeRole.ASSISTANT,
                    List.of(
                        new LyCodeThinkingBlock("Need to inspect the file.", Map.of()),
                        new LyCodeToolCallBlock(
                            "toolu_1",
                            "read_file",
                            "",
                            Map.of("input", Map.of("path", "pom.xml"))
                        )
                    ),
                    Map.of("messageId", "msg-2")
                ),
                new LyCodeMessage(
                    LyCodeRole.TOOL_RESULT,
                    List.of(new LyCodeToolResultBlock("toolu_1", "<project/>", false, Map.of())),
                    Map.of("messageId", "msg-3")
                )
            ),
            List.of(tool),
            new LyCodeGenerationOptions(Optional.of(2048), Optional.of(0.2), Map.of()),
            Map.of()
        );
        AnthropicProviderConfig config = config();

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config);

        assertThat(body.get("model").asText()).isEqualTo("claude-sonnet-4-5");
        assertThat(body.get("stream").asBoolean()).isTrue();
        assertThat(body.get("max_tokens").asInt()).isEqualTo(2048);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.get("system").asText()).isEqualTo("You are concise.");
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/type").asText()).isEqualTo("text");
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("Inspect pom.xml");
        assertThat(body.at("/messages/1/role").asText()).isEqualTo("assistant");
        assertThat(body.at("/messages/1/content/0/type").asText()).isEqualTo("tool_use");
        assertThat(body.at("/messages/1/content/0/id").asText()).isEqualTo("toolu_1");
        assertThat(body.at("/messages/1/content/0/name").asText()).isEqualTo("read_file");
        assertThat(body.at("/messages/1/content/0/input/path").asText()).isEqualTo("pom.xml");
        assertThat(body.at("/messages/2/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/2/content/0/type").asText()).isEqualTo("tool_result");
        assertThat(body.at("/messages/2/content/0/tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(body.at("/messages/2/content/0/content").asText()).isEqualTo("<project/>");
        assertThat(body.at("/messages/2/content/0/is_error").asBoolean()).isFalse();
        assertThat(body.at("/tools/0/name").asText()).isEqualTo("read_file");
        assertThat(body.at("/tools/0/description").asText()).isEqualTo("Read a local file.");
        assertThat(body.at("/tools/0/input_schema/properties/path/type").asText()).isEqualTo("string");
        assertThat(body.get("thinking")).isNull();
        assertThat(body.get("api_key")).isNull();
        assertThat(body.toString()).doesNotContain("test-key");
        assertThat(body.toString()).doesNotContain("Need to inspect the file.");
        assertThat(body.findValuesAsText("type")).doesNotContain("thinking");
    }

    @Test
    void omitsExtendedThinkingRequestParameterUntilSignedThinkingReplayIsSupported() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-2",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.MAX),
            ThinkingLevel.MAX,
            "",
            List.of(new LyCodeMessage(
                LyCodeRole.USER,
                List.of(new LyCodeTextBlock("hello", Map.of())),
                Map.of()
            )),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.get("thinking")).isNull();
    }

    @Test
    void foldsSystemLocalMessagesIntoTopLevelSystemPrompt() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-3",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "Base system.",
            List.of(
                new LyCodeMessage(
                    LyCodeRole.SYSTEM_LOCAL,
                    List.of(new LyCodeTextBlock("Compaction instruction.", Map.of())),
                    Map.of("messageId", "sys-1")
                ),
                new LyCodeMessage(
                    LyCodeRole.USER,
                    List.of(new LyCodeTextBlock("hello", Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.get("system").asText()).isEqualTo("Base system.\n\nCompaction instruction.");
        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.toString()).doesNotContain("\"role\":\"system\"");
        assertThat(body.toString()).doesNotContain("\"role\":\"SYSTEM_LOCAL\"");
    }

    @Test
    void omitsAssistantMessagesThatOnlyContainGenericThinking() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-4",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(
                new LyCodeMessage(
                    LyCodeRole.ASSISTANT,
                    List.of(new LyCodeThinkingBlock("Hidden reasoning without Anthropic signature.", Map.of())),
                    Map.of("messageId", "msg-thinking")
                ),
                new LyCodeMessage(
                    LyCodeRole.USER,
                    List.of(new LyCodeTextBlock("continue", Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("continue");
        assertThat(body.toString()).doesNotContain("Hidden reasoning without Anthropic signature.");
    }

    @Test
    void mapsToolResultImageAttachmentsIntoAnthropicToolResultContentBlocks() {
        LyCodeModelRequest request = new LyCodeModelRequest(
            "req-5",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LyCodeMessage(
                LyCodeRole.TOOL_RESULT,
                List.of(
                    new LyCodeToolResultBlock("toolu_1", "Read image file [image/png]", false, Map.of()),
                    new LyCodeAttachmentBlock(
                        "att-1",
                        "Image: image/png",
                        "image/png",
                        Map.of("imageUrl", "data:image/png;base64,AAA", "detail", "high")
                    )
                ),
                Map.of()
            )),
            List.of(),
            LyCodeGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.at("/messages/0/content/0/type").asText()).isEqualTo("tool_result");
        assertThat(body.at("/messages/0/content/0/content/0/type").asText()).isEqualTo("text");
        assertThat(body.at("/messages/0/content/0/content/0/text").asText()).isEqualTo("Read image file [image/png]");
        assertThat(body.at("/messages/0/content/0/content/1/type").asText()).isEqualTo("image");
        assertThat(body.at("/messages/0/content/0/content/1/source/type").asText()).isEqualTo("base64");
        assertThat(body.at("/messages/0/content/0/content/1/source/media_type").asText()).isEqualTo("image/png");
        assertThat(body.at("/messages/0/content/0/content/1/source/data").asText()).isEqualTo("AAA");
        assertThat(body.at("/messages/0/content")).hasSize(1);
        assertThat(body.toString()).doesNotContain("Image: image/png");
    }

    private static AnthropicProviderConfig config() {
        return new AnthropicProviderConfig(
            "anthropic",
            URI.create("https://api.anthropic.com/v1"),
            "test-key",
            "2023-06-01",
            Duration.ofSeconds(30),
            3,
            Map.of()
        );
    }
}
