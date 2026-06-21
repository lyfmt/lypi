package cn.lypi.ai.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.spec.LypiGenerationOptions;
import cn.lypi.ai.spec.LypiMessage;
import cn.lypi.ai.spec.LypiModelRequest;
import cn.lypi.ai.spec.LypiRole;
import cn.lypi.ai.spec.LypiTextBlock;
import cn.lypi.ai.spec.LypiThinkingBlock;
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

class AnthropicMessagesRequestBuilderTest {
    @Test
    void buildsMessagesRequestWithSystemToolsToolUseToolResultAndRequestThinkingBudget() {
        LypiToolSpec tool = new LypiToolSpec(
            "read_file",
            "Read a local file.",
            Map.of(
                "type", "object",
                "properties", Map.of("path", Map.of("type", "string")),
                "required", List.of("path")
            )
        );
        LypiModelRequest request = new LypiModelRequest(
            "req-1",
            new ModelSelection("anthropic", "claude-sonnet-4-5", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            "You are concise.",
            List.of(
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("Inspect pom.xml", Map.of())),
                    Map.of("messageId", "msg-1")
                ),
                new LypiMessage(
                    LypiRole.ASSISTANT,
                    List.of(
                        new LypiThinkingBlock("Need to inspect the file.", Map.of()),
                        new LypiToolCallBlock(
                            "toolu_1",
                            "read_file",
                            "",
                            Map.of("input", Map.of("path", "pom.xml"))
                        )
                    ),
                    Map.of("messageId", "msg-2")
                ),
                new LypiMessage(
                    LypiRole.TOOL_RESULT,
                    List.of(new LypiToolResultBlock("toolu_1", "<project/>", false, Map.of())),
                    Map.of("messageId", "msg-3")
                )
            ),
            List.of(tool),
            new LypiGenerationOptions(Optional.of(2048), Optional.of(0.2), Map.of()),
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
        assertThat(body.at("/thinking/type").asText()).isEqualTo("enabled");
        assertThat(body.at("/thinking/budget_tokens").asInt()).isGreaterThan(0);
        assertThat(body.at("/thinking/budget_tokens").asInt()).isLessThan(body.get("max_tokens").asInt());
        assertThat(body.get("api_key")).isNull();
        assertThat(body.toString()).doesNotContain("test-key");
        assertThat(body.toString()).doesNotContain("Need to inspect the file.");
        assertThat(body.findValuesAsText("type")).doesNotContain("thinking");
    }

    @Test
    void omitsThinkingWhenThinkingIsOff() {
        LypiModelRequest request = new LypiModelRequest(
            "req-2",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(new LypiMessage(
                LypiRole.USER,
                List.of(new LypiTextBlock("hello", Map.of())),
                Map.of()
            )),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.get("thinking")).isNull();
    }

    @Test
    void foldsSystemLocalMessagesIntoTopLevelSystemPrompt() {
        LypiModelRequest request = new LypiModelRequest(
            "req-3",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "Base system.",
            List.of(
                new LypiMessage(
                    LypiRole.SYSTEM_LOCAL,
                    List.of(new LypiTextBlock("Compaction instruction.", Map.of())),
                    Map.of("messageId", "sys-1")
                ),
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("hello", Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LypiGenerationOptions.defaults(),
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
        LypiModelRequest request = new LypiModelRequest(
            "req-4",
            new ModelSelection("anthropic", "claude-haiku", ThinkingLevel.OFF),
            ThinkingLevel.OFF,
            "",
            List.of(
                new LypiMessage(
                    LypiRole.ASSISTANT,
                    List.of(new LypiThinkingBlock("Hidden reasoning without Anthropic signature.", Map.of())),
                    Map.of("messageId", "msg-thinking")
                ),
                new LypiMessage(
                    LypiRole.USER,
                    List.of(new LypiTextBlock("continue", Map.of())),
                    Map.of()
                )
            ),
            List.of(),
            LypiGenerationOptions.defaults(),
            Map.of()
        );

        JsonNode body = new AnthropicMessagesRequestBuilder().build(request, config());

        assertThat(body.get("messages")).hasSize(1);
        assertThat(body.at("/messages/0/role").asText()).isEqualTo("user");
        assertThat(body.at("/messages/0/content/0/text").asText()).isEqualTo("continue");
        assertThat(body.toString()).doesNotContain("Hidden reasoning without Anthropic signature.");
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
