package cn.lypi.boot.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.ai.DefaultApiProviderRegistry;
import cn.lypi.ai.DefaultModelPort;
import cn.lypi.ai.DefaultModelRegistry;
import cn.lypi.ai.ProviderAdapterApiProvider;
import cn.lypi.ai.RuntimeModelRegistry;
import cn.lypi.ai.model.DiscoveredModelDefaults;
import cn.lypi.ai.model.RemoteModelDiscoveryClient;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.TextDelta;
import cn.lypi.contracts.model.ThinkingDelta;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.ProviderLoginResult;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class OpenAiCompatibleLoginRealEndToEndTest {
    private static final URI BASE_URL = URI.create(System.getProperty(
        "lypi.openai-compatible.e2e.base-url",
        "https://opencode.ai/zen/go/v1"
    ));
    private static final String CHANNEL = "compat-live";
    private static final String THINKING_MODEL = System.getProperty(
        "lypi.openai-compatible.e2e.thinking-model",
        "deepseek-v4-flash"
    );
    private static final String IMAGE_MODEL = System.getProperty(
        "lypi.openai-compatible.e2e.image-model",
        "kimi-k2.6"
    );
    private static final String TOOL_NAME = "lypi_compatibility_probe";
    private static final String TOOL_TOKEN = "LIVE_TOOL_OK";
    private static final String IMAGE_TOKEN = "RED_BLUE";
    private static final String RED_BLUE_PNG = "data:image/png;base64,"
        + "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAIAAAB7QOjdAAAAD0lEQVR4nGP4z8DAwPAfAAcAAf9+CLHQAAAAAElFTkSuQmCC";

    @TempDir
    Path tempDir;

    @Test
    @Timeout(value = 300, unit = TimeUnit.SECONDS)
    void logsInAndStreamsThinkingToolContinuationAndImageChatCompletions() {
        assumeTrue(
            Boolean.getBoolean("lypi.openai-compatible.e2e"),
            "Enable with -Dlypi.openai-compatible.e2e=true"
        );
        String apiKey = apiKey();
        RuntimeModelRegistry modelRegistry = new DefaultModelRegistry(List.of());
        ProviderAdapterApiProvider dispatcher = new ProviderAdapterApiProvider(
            ApiStyle.OPENAI_COMPATIBLE,
            List.of()
        );
        Path home = tempDir.resolve("home");
        OpenAiCompatibleProviderLoginService loginService = new OpenAiCompatibleProviderLoginService(
            new RemoteModelDiscoveryClient(),
            modelRegistry,
            dispatcher,
            new LoginProviderPropertiesStore(home),
            discoveredDefaults()
        );

        ProviderLoginResult login = safely(
            "OpenAI-compatible login",
            () -> loginService.register(CHANNEL, BASE_URL.toString(), apiKey)
        );
        assertThat(login.provider()).isEqualTo(CHANNEL);
        assertThat(login.models()).isNotEmpty();
        assertThat(login.models()).allSatisfy(model -> {
            assertThat(model.provider()).isEqualTo(CHANNEL);
            assertThat(model.contextWindow()).isPositive();
            assertThat(model.maxOutputTokens()).isPositive();
        });
        assertManagedProperties(home.resolve(".ly-pi/login-providers.properties"));
        assertThat(modelRegistry.list()).containsExactlyElementsOf(login.models());

        ModelDescriptor thinkingDescriptor = requireModel(login, THINKING_MODEL, "thinking and tool");
        assertThat(thinkingDescriptor.supportsThinking()).isTrue();
        ModelDescriptor imageDescriptor = requireModel(login, IMAGE_MODEL, "image");
        assertThat(imageDescriptor.supportsImageInput()).isTrue();
        DefaultModelPort modelPort = new DefaultModelPort(
            modelRegistry,
            new DefaultApiProviderRegistry(List.of(dispatcher))
        );
        List<AssistantStreamEvent> allEvents = new ArrayList<>();

        List<AssistantStreamEvent> thinkingEvents = stream(
            modelPort,
            context(
                thinkingDescriptor,
                ThinkingLevel.HIGH,
                List.of(userMessage(
                    "thinking-user",
                    "Think briefly, then answer in one short sentence that contains LIVE_THINKING_OK."
                ))
            ),
            new ToolRegistrySnapshot(List.of()),
            "HIGH thinking chat"
        );
        allEvents.addAll(thinkingEvents);
        assertThat(joinedThinking(thinkingEvents)).isNotBlank();
        assertThat(joinedText(thinkingEvents)).isNotBlank();
        assertCompleted(thinkingEvents);

        ToolRegistrySnapshot tools = toolRegistry();
        AgentMessage toolPrompt = userMessage(
            "tool-user",
            "Call lypi_compatibility_probe exactly once with value LIVE_TOOL_OK. "
                + "Do not answer before calling it. After receiving the tool result, return LIVE_TOOL_OK."
        );
        List<AssistantStreamEvent> toolEvents = stream(
            modelPort,
            context(thinkingDescriptor, ThinkingLevel.HIGH, List.of(toolPrompt)),
            tools,
            "tool-call chat"
        );
        allEvents.addAll(toolEvents);
        String toolThinking = joinedThinking(toolEvents);
        assertThat(toolThinking).isNotBlank();
        ToolCallDelta toolCall = completeToolCall(toolEvents);
        assertThat(toolCall.toolName()).isEqualTo(TOOL_NAME);
        assertThat(toolCall.toolUseId()).isNotBlank();
        assertThat(toolCall.partialInput()).containsEntry("value", TOOL_TOKEN);
        assertCompleted(toolEvents);

        AgentMessage assistantToolCall = assistantToolCall(toolCall, toolThinking, joinedText(toolEvents));
        AgentMessage toolResult = toolResult(toolCall.toolUseId());
        List<AssistantStreamEvent> continuationEvents = stream(
            modelPort,
            context(
                thinkingDescriptor,
                ThinkingLevel.HIGH,
                List.of(toolPrompt, assistantToolCall, toolResult)
            ),
            new ToolRegistrySnapshot(List.of()),
            "tool-result continuation chat"
        );
        allEvents.addAll(continuationEvents);
        assertThat(joinedText(continuationEvents)).contains(TOOL_TOKEN);
        assertCompleted(continuationEvents);

        ContextSnapshot imageContext = context(
            imageDescriptor,
            ThinkingLevel.OFF,
            List.of(imageMessage())
        );
        List<AssistantStreamEvent> imageEvents = stream(
            modelPort,
            imageContext,
            new ToolRegistrySnapshot(List.of()),
            "image chat"
        );
        allEvents.addAll(imageEvents);
        assertThat(joinedText(imageEvents)).contains(IMAGE_TOKEN);
        assertCompleted(imageEvents);

        String eventTypes = allEvents.stream()
            .map(event -> event.getClass().getSimpleName())
            .distinct()
            .sorted()
            .collect(Collectors.joining(","));
        System.out.printf(
            "OpenAI-compatible E2E models=%d thinkingModel=%s toolModel=%s imageModel=%s events=%s%n",
            login.models().size(),
            THINKING_MODEL,
            THINKING_MODEL,
            IMAGE_MODEL,
            eventTypes
        );
    }

    private static String apiKey() {
        String configured = System.getProperty("lypi.openai-compatible.e2e.api-key");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        String authEntry = System.getProperty(
            "lypi.openai-compatible.e2e.auth-entry",
            "opencode-go"
        );
        Path authFile = Path.of(System.getProperty("user.home"), ".pi", "agent", "auth.json");
        try {
            JsonNode credential = new ObjectMapper().readTree(authFile.toFile()).path(authEntry);
            String apiKey = credential.path("key").asText();
            if (!apiKey.isBlank()) {
                return apiKey;
            }
        } catch (IOException | RuntimeException error) {
            throw new AssertionError("Unable to load the configured E2E credential.");
        }
        throw new AssertionError("The configured E2E credential is missing an API key.");
    }

    private static DiscoveredModelDefaults discoveredDefaults() {
        return new DiscoveredModelDefaults(
            DiscoveredModelDefaults.DEFAULT_CONTEXT_WINDOW,
            DiscoveredModelDefaults.DEFAULT_MAX_OUTPUT_TOKENS,
            DiscoveredModelDefaults.DEFAULT_SUPPORTS_THINKING,
            DiscoveredModelDefaults.DEFAULT_SUPPORTS_IMAGE_INPUT,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static ModelDescriptor requireModel(
        ProviderLoginResult login,
        String modelId,
        String purpose
    ) {
        return login.models().stream()
            .filter(model -> model.modelId().equals(modelId))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "Required " + purpose + " E2E model is unavailable: " + modelId
            ));
    }

    private static void assertManagedProperties(Path storeFile) {
        assertThat(storeFile).exists();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(storeFile)) {
            properties.load(input);
        } catch (IOException error) {
            throw new AssertionError("Unable to inspect managed E2E provider properties.");
        }
        boolean cachesModels = properties.stringPropertyNames().stream()
            .anyMatch(name -> name.contains(".models["));
        boolean disablesThinking = properties.stringPropertyNames().stream()
            .filter(name -> name.endsWith(".supports-thinking"))
            .map(properties::getProperty)
            .anyMatch("false"::equalsIgnoreCase);
        assertThat(cachesModels).as("managed login properties cache no model list").isFalse();
        assertThat(disablesThinking).as("managed login properties do not disable thinking").isFalse();
    }

    private static ContextSnapshot context(
        ModelDescriptor descriptor,
        ThinkingLevel thinkingLevel,
        List<AgentMessage> messages
    ) {
        int autoCompactThreshold = (int) Math.min(
            Integer.MAX_VALUE,
            Math.max(1L, descriptor.contextWindow() * 4L / 5L)
        );
        return new ContextSnapshot(
            new SystemPrompt(
                "Follow the user's compatibility-test instructions exactly.",
                List.of("e2e"),
                "e2e"
            ),
            messages,
            new ModelSelection(CHANNEL, descriptor.modelId(), thinkingLevel),
            thinkingLevel,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(
                0,
                descriptor.contextWindow(),
                autoCompactThreshold,
                descriptor.maxOutputTokens(),
                Math.min(8_192, descriptor.maxOutputTokens()),
                0,
                0,
                BigDecimal.ZERO
            )
        );
    }

    private static AgentMessage userMessage(String id, String text) {
        return message(id, MessageRole.USER, MessageKind.TEXT, List.of(new TextContentBlock(text)));
    }

    private static AgentMessage imageMessage() {
        return message(
            "image-user",
            MessageRole.USER,
            MessageKind.ATTACHMENT,
            List.of(
                new TextContentBlock(
                    "Inspect the attached two-pixel image. If its left pixel is red and its right pixel is blue, "
                        + "reply with exactly RED_BLUE."
                ),
                new AttachmentContentBlock(
                    "red-blue-image",
                    "two-pixel red and blue image",
                    "image/png",
                    Map.of("imageUrl", RED_BLUE_PNG, "detail", "high")
                )
            )
        );
    }

    private static AgentMessage assistantToolCall(
        ToolCallDelta toolCall,
        String thinking,
        String text
    ) {
        List<ContentBlock> content = new ArrayList<>();
        if (!thinking.isBlank()) {
            content.add(new ThinkingContentBlock(thinking));
        }
        if (!text.isBlank()) {
            content.add(new TextContentBlock(text));
        }
        content.add(new ToolCallContentBlock(
            toolCall.toolUseId(),
            toolCall.toolName(),
            "",
            Map.of("input", toolCall.partialInput())
        ));
        return message("tool-assistant", MessageRole.ASSISTANT, MessageKind.TOOL_CALL, content);
    }

    private static AgentMessage toolResult(String toolUseId) {
        return message(
            "tool-result",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, TOOL_TOKEN, false))
        );
    }

    private static AgentMessage message(
        String id,
        MessageRole role,
        MessageKind kind,
        List<ContentBlock> content
    ) {
        return new AgentMessage(
            id,
            role,
            kind,
            content,
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static ToolRegistrySnapshot toolRegistry() {
        return new ToolRegistrySnapshot(List.of(new ToolDescriptor(
            TOOL_NAME,
            List.of(),
            "Required compatibility probe. Call it with the exact requested value.",
            new JsonSchema(Map.of(
                "type", "object",
                "properties", Map.of("value", Map.of(
                    "type", "string",
                    "enum", List.of(TOOL_TOKEN)
                )),
                "required", List.of("value"),
                "additionalProperties", false
            )),
            true,
            false
        )));
    }

    private static List<AssistantStreamEvent> stream(
        DefaultModelPort modelPort,
        ContextSnapshot context,
        ToolRegistrySnapshot tools,
        String operation
    ) {
        return safely(operation, () -> {
            try (AssistantEventStream stream = modelPort.stream(context, tools, () -> false)) {
                return StreamSupport.stream(stream.spliterator(), false).toList();
            }
        });
    }

    private static ToolCallDelta completeToolCall(List<AssistantStreamEvent> events) {
        return events.stream()
            .filter(ToolCallDelta.class::isInstance)
            .map(ToolCallDelta.class::cast)
            .filter(ToolCallDelta::complete)
            .reduce((first, second) -> second)
            .orElseThrow(() -> new AssertionError("Tool-call chat emitted no complete tool call."));
    }

    private static String joinedThinking(List<AssistantStreamEvent> events) {
        return events.stream()
            .filter(ThinkingDelta.class::isInstance)
            .map(ThinkingDelta.class::cast)
            .map(ThinkingDelta::text)
            .collect(Collectors.joining());
    }

    private static String joinedText(List<AssistantStreamEvent> events) {
        return events.stream()
            .filter(TextDelta.class::isInstance)
            .map(TextDelta.class::cast)
            .map(TextDelta::text)
            .collect(Collectors.joining());
    }

    private static void assertCompleted(List<AssistantStreamEvent> events) {
        assertThat(events.stream().anyMatch(AssistantDone.class::isInstance))
            .as("provider stream emitted AssistantDone")
            .isTrue();
    }

    private static <T> T safely(String operation, UnsafeSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException error) {
            throw new AssertionError(operation + " failed (" + error.getClass().getSimpleName() + ").");
        }
    }

    @FunctionalInterface
    private interface UnsafeSupplier<T> {
        T get();
    }
}
