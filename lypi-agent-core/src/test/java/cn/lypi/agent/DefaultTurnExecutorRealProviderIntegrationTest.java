package cn.lypi.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cn.lypi.agent.compact.NoopCompactionCoordinator;
import cn.lypi.agent.compact.NoopToolMicroCompactor;
import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.ai.provider.openai.OpenAiCompatibleProviderAdapter;
import cn.lypi.ai.provider.openai.OpenAiProviderConfig;
import cn.lypi.ai.transport.HttpSseProviderTransport;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.TurnEndEvent;
import cn.lypi.contracts.event.TurnStartEvent;
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
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class DefaultTurnExecutorRealProviderIntegrationTest {
    @Test
    @Timeout(120)
    void executesOneTurnWithConfiguredRealProvider() {
        assumeTrue(Boolean.getBoolean("lypi.agent.real-provider.e2e"), "Enable with -Dlypi.agent.real-provider.e2e=true");
        RealProviderSettings settings = RealProviderSettings.fromSystemProperties();
        AgentCoreTestFixtures.InMemorySessionManager session = new AgentCoreTestFixtures.InMemorySessionManager();
        AgentCoreTestFixtures.StubToolRuntime tools = new AgentCoreTestFixtures.StubToolRuntime();
        AgentCoreTestFixtures.RecordingEventBus eventBus = new AgentCoreTestFixtures.RecordingEventBus();
        RealProviderPort provider = new RealProviderPort(settings);
        ContextAssembler assembler = request -> new ContextAssembly(
            realContext(settings, session.messages()),
            List.of(),
            List.of(),
            List.of(),
            false
        );
        DefaultTurnExecutor executor = new DefaultTurnExecutor(
            new AgentCoreRuntimePorts(
                Path.of("."),
                session,
                provider,
                tools,
                AgentCoreTestFixtures.allowAllSecurityRuntime(),
                AgentCoreTestFixtures.fixedResourceRuntime("system"),
                eventBus,
                assembler,
                new NoopToolMicroCompactor(),
                new NoopCompactionCoordinator(),
                new NoopMemoryExtractionWorker()
            ),
            TurnIds.fixed("turn-real-1", "msg-real-user", "msg-real-fallback"),
            Clock.fixed(AgentCoreTestFixtures.NOW, ZoneOffset.UTC)
        );

        TurnState state = executor.execute(new TurnRequest(
            "session-real-1",
            "请用一句简短中文回复：agent-core 真实 provider turn 测试通过",
            Optional.empty(),
            () -> false,
            0
        ));

        assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
        assertThat(provider.contexts).hasSize(1);
        assertThat(session.messages()).extracting(AgentMessage::role)
            .containsExactly(MessageRole.USER, MessageRole.ASSISTANT);
        AgentMessage assistant = session.messages().get(1);
        assertThat(assistant.kind()).isEqualTo(MessageKind.TEXT);
        assertThat(assistant.content().stream().map(ContentBlock::text).reduce("", String::concat)).isNotBlank();
        assertThat(eventBus.events).extracting(AgentEvent::getClass)
            .contains(TurnStartEvent.class, MessageStartEvent.class, MessageDeltaEvent.class, MessageEndEvent.class, TurnEndEvent.class);
        assertThat(eventBus.events)
            .filteredOn(MessageDeltaEvent.class::isInstance)
            .map(MessageDeltaEvent.class::cast)
            .anySatisfy(delta -> assertThat(delta.delta()).isNotBlank());
    }

    @Test
    void preAbortedSignalDoesNotOpenRealProviderStream() {
        assumeFalse(Boolean.getBoolean("lypi.agent.real-provider.e2e"), "This guard is deterministic and runs without network.");
        RealProviderPort provider = new RealProviderPort(new RealProviderSettings(
            URI.create("https://example.invalid/v1"),
            "test-key",
            "gpt-5.4",
            ThinkingLevel.MEDIUM
        ));

        AbortSignal aborted = () -> true;

        assertThat(provider.stream(realContext(provider.settings, List.of()), aborted))
            .isInstanceOf(AgentCoreTestFixtures.ListAssistantEventStream.class);
        assertThat(provider.openedStreams).isZero();
    }

    private static ContextSnapshot realContext(RealProviderSettings settings, List<AgentMessage> messages) {
        return new ContextSnapshot(
            new SystemPrompt("你是 ly-pi 自动化测试里的简洁助手。只用一句中文回答。", List.of("real-provider-test"), "hash"),
            List.copyOf(messages),
            new ModelSelection("real-provider", settings.model(), settings.thinkingLevel()),
            settings.thinkingLevel(),
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 128_000, 100_000, 16_384, 8_192, 0, 0, BigDecimal.ZERO)
        );
    }

    private static final class RealProviderPort implements AiProviderRuntimePort {
        private final RealProviderSettings settings;
        private final OpenAiCompatibleProviderAdapter adapter;
        private final ModelDescriptor descriptor;
        private final List<ContextSnapshot> contexts = new java.util.ArrayList<>();
        private int openedStreams;

        private RealProviderPort(RealProviderSettings settings) {
            this.settings = settings;
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
                    throw new IllegalStateException("Real provider agent-core test uses HTTP SSE transport only.");
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
            if (signal.aborted()) {
                return new AgentCoreTestFixtures.ListAssistantEventStream(List.of());
            }
            openedStreams++;
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
