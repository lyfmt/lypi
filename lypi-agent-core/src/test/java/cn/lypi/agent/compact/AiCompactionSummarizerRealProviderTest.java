package cn.lypi.agent.compact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class AiCompactionSummarizerRealProviderTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @Timeout(120)
    void summarizesContextWithConfiguredRealProvider() {
        assumeTrue(Boolean.getBoolean("lypi.agent.real-provider.e2e"), "Enable with -Dlypi.agent.real-provider.e2e=true");
        RealProviderSettings settings = RealProviderSettings.fromSystemProperties();
        RealProviderPort provider = new RealProviderPort(settings);
        AiCompactionSummarizer summarizer = new AiCompactionSummarizer(
            provider,
            new CompactSummaryContextBuilder(new CompactSummaryInstructionFactory()),
            CompactionSummaryOptions.defaults()
        );

        CompactSummaryResult result = summarizer.summarize(request(settings, () -> false));

        assertThat(result.summary()).isNotBlank();
        assertThat(result.summary()).doesNotContain("<summary", "</summary>", "<analysis", "```");
        assertThat(provider.contexts).hasSize(1);
        ContextSnapshot sentContext = provider.contexts.getFirst();
        assertThat(sentContext.systemPrompt().content()).contains("ly-pi compact summary");
        assertThat(sentContext.model()).isEqualTo(new ModelSelection("real-provider", settings.model(), settings.thinkingLevel()));
        assertThat(sentContext.thinkingLevel()).isEqualTo(settings.thinkingLevel());
        assertThat(sentContext.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(sentContext.permissionMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(sentContext.messages()).extracting(AgentMessage::id)
            .startsWith("msg-user-1", "msg-assistant-1");
        assertThat(sentContext.messages().getLast().id()).isEqualTo("compact-summary-instruction");
    }

    private static CompactSummaryRequest request(RealProviderSettings settings, AbortSignal abortSignal) {
        ContextSnapshot context = context(settings);
        return new CompactSummaryRequest(
            context,
            new CompactionPlan(
                "entry-user-1",
                "entry-assistant-1",
                List.of("entry-user-1"),
                CompactionKind.SESSION
            ),
            branchEntries(context),
            abortSignal
        );
    }

    private static ContextSnapshot context(RealProviderSettings settings) {
        return new ContextSnapshot(
            new SystemPrompt("ly-pi compact summary real provider test system prompt", List.of("real-provider-test"), "hash"),
            List.of(
                userMessage("msg-user-1", "用户要求为多个模块补真实测试和本地覆盖测试。"),
                assistantMessage("msg-assistant-1", "已确认计划并开始执行，正在补 agent-core 测试。")
            ),
            new ModelSelection("real-provider", settings.model(), settings.thinkingLevel()),
            settings.thinkingLevel(),
            AgentMode.PLAN,
            PermissionMode.PLAN,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 11, 7, BigDecimal.ZERO)
        );
    }

    private static List<SessionEntry> branchEntries(ContextSnapshot context) {
        return List.of(
            new MessageEntry("entry-user-1", "", context.messages().get(0), NOW),
            new MessageEntry("entry-assistant-1", "entry-user-1", context.messages().get(1), NOW)
        );
    }

    private static AgentMessage userMessage(String id, String text) {
        return textMessage(id, MessageRole.USER, MessageKind.TEXT, text);
    }

    private static AgentMessage assistantMessage(String id, String text) {
        return textMessage(id, MessageRole.ASSISTANT, MessageKind.TEXT, text);
    }

    private static AgentMessage textMessage(String id, MessageRole role, MessageKind kind, String text) {
        return new AgentMessage(
            id,
            role,
            kind,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static final class RealProviderPort implements AiProviderRuntimePort {
        private final OpenAiCompatibleProviderAdapter adapter;
        private final ModelDescriptor descriptor;
        private final List<ContextSnapshot> contexts = new ArrayList<>();

        private RealProviderPort(RealProviderSettings settings) {
            HttpSseProviderTransport sseTransport = new HttpSseProviderTransport();
            this.adapter = new OpenAiCompatibleProviderAdapter(
                new OpenAiProviderConfig(
                    "real-provider",
                    settings.baseUrl(),
                    Optional.empty(),
                    "/v1/responses",
                    settings.apiKey(),
                    RequestStyle.RESPONSES,
                    RequestStyle.RESPONSES,
                    TransportMode.SSE,
                    Duration.ofSeconds(60),
                    0,
                    Map.of()
                ),
                (request, signal) -> {
                    throw new IllegalStateException("Real provider compact test uses HTTP SSE transport only.");
                },
                sseTransport,
                sseTransport
            );
            this.descriptor = new ModelDescriptor(
                "real-provider",
                settings.model(),
                settings.baseUrl(),
                ApiStyle.OPENAI_COMPATIBLE,
                128_000,
                16_384,
                true,
                false,
                new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
                Map.of()
            );
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, AbortSignal signal) {
            contexts.add(context);
            return adapter.stream(context, descriptor, signal);
        }
    }

    private record RealProviderSettings(
        URI baseUrl,
        String apiKey,
        String model,
        ThinkingLevel thinkingLevel
    ) {
        private static RealProviderSettings fromSystemProperties() {
            return new RealProviderSettings(
                requiredUri("lypi.provider.e2e.base-url"),
                required("lypi.provider.e2e.api-key"),
                required("lypi.provider.e2e.model"),
                ThinkingLevel.valueOf(System.getProperty("lypi.provider.e2e.thinking", "OFF").toUpperCase(java.util.Locale.ROOT))
            );
        }

        private static URI requiredUri(String key) {
            return URI.create(required(key));
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            assumeTrue(value != null && !value.isBlank(), "Missing required system property: " + key);
            return value;
        }
    }
}
