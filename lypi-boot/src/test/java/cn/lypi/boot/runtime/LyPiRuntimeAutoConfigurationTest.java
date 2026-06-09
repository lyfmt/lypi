package cn.lypi.boot.runtime;

import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.bootstrap.BootstrapContext;
import cn.lypi.contracts.bootstrap.BootstrapRequest;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.runtime.LyPiRuntime;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.boot.ai.LyPiAiAutoConfiguration;
import cn.lypi.boot.BootstrapService;
import cn.lypi.boot.tool.LyPiToolAutoConfiguration;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.security.DefaultPolicyEngine;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.ApplicationRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiRuntimeAutoConfigurationTest {
    @TempDir
    Path tempDir;

    private ApplicationContextRunner runtimeConfiguration() {
        return new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withPropertyValues("lypi.runtime.cwd=" + tempDir);
    }

    private ApplicationContextRunner runtimeAutoConfigurations() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiAiAutoConfiguration.class,
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues("lypi.runtime.cwd=" + tempDir);
    }

    @Test
    void createsDefaultInMemoryEventBusWhenMissing() {
        runtimeConfiguration()
            .run(context -> {
                assertThat(context).hasSingleBean(EventBus.class);
                assertThat(context.getBean(EventBus.class)).isInstanceOf(InMemoryEventBus.class);
            });
    }

    @Test
    void keepsUserProvidedEventBus() {
        EventBus customEventBus = new RecordingEventBus();

        runtimeConfiguration()
            .withBean(EventBus.class, () -> customEventBus)
            .run(context -> {
                assertThat(context).hasSingleBean(EventBus.class);
                assertThat(context.getBean(EventBus.class)).isSameAs(customEventBus);
            });
    }

    @Test
    void attachesTransportsToSameEventBusWhenSessionStateIsAvailable() {
        RecordingTransport first = new RecordingTransport("first");
        RecordingTransport second = new RecordingTransport("second");
        SessionRuntimeState state = sessionState();

        runtimeConfiguration()
            .withBean("firstTransport", TransportAdapter.class, () -> first)
            .withBean("secondTransport", TransportAdapter.class, () -> second)
            .withBean(SessionRuntimeState.class, () -> state)
            .run(context -> {
                EventBus eventBus = context.getBean(EventBus.class);

                assertThat(first.events.get()).isSameAs(eventBus);
                assertThat(second.events.get()).isSameAs(eventBus);
                assertThat(first.state.get()).isSameAs(state);
                assertThat(second.state.get()).isSameAs(state);
            });
    }

    @Test
    void connectorCanAttachExplicitSessionStateWhenNoStateBeanExists() {
        RecordingTransport transport = new RecordingTransport("transport");

        runtimeConfiguration()
            .withBean(TransportAdapter.class, () -> transport)
            .run(context -> {
                SessionRuntimeState state = sessionState();
                TransportEventConnector connector = context.getBean(TransportEventConnector.class);
                connector.attachAll(state);

                assertThat(transport.events.get()).isSameAs(context.getBean(EventBus.class));
                assertThat(transport.state.get()).isSameAs(state);
            });
    }

    @Test
    void createsDefaultRuntimeBeanGraph() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(SecurityRuntimePort.class);
                assertThat(context.getBean(SecurityRuntimePort.class)).isInstanceOf(DefaultPolicyEngine.class);
                assertThat(context).hasSingleBean(SessionManagerPort.class);
                assertThat(context).hasSingleBean(ResourceRuntimePort.class);
                assertThat(context).hasSingleBean(AiProviderRuntimePort.class);
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                assertThat(context).hasSingleBean(AgentCorePort.class);
                assertThat(context).hasSingleBean(LyPiRuntime.class);

                LyPiRuntime runtime = context.getBean(LyPiRuntime.class);
                assertThat(runtime.sessionManager()).isSameAs(context.getBean(SessionManagerPort.class));
                assertThat(runtime.agentCore()).isSameAs(context.getBean(AgentCorePort.class));
                assertThat(runtime.aiProvider()).isSameAs(context.getBean(AiProviderRuntimePort.class));
                assertThat(runtime.toolRuntime()).isSameAs(context.getBean(ToolRuntimePort.class));
                assertThat(runtime.securityRuntime()).isSameAs(context.getBean(SecurityRuntimePort.class));
                assertThat(runtime.resourceRuntime()).isSameAs(context.getBean(ResourceRuntimePort.class));
            });
    }

    @Test
    void bootstrapServiceBuildsContextFromRuntimePorts() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .run(context -> {
                assertThat(context).hasSingleBean(BootstrapService.class);

                BootstrapService bootstrapService = context.getBean(BootstrapService.class);
                BootstrapContext bootstrap = bootstrapService.bootstrap(new BootstrapRequest(
                    tempDir,
                    List.of(),
                    Optional.of("session-bootstrap"),
                    Optional.empty()
                ));

                assertThat(bootstrap.cwd()).isEqualTo(tempDir.toAbsolutePath().normalize());
                assertThat(bootstrap.projectRoot()).isEqualTo(tempDir.toAbsolutePath().normalize());
                assertThat(bootstrap.session().sessionId()).isEqualTo("session-bootstrap");
                assertThat(bootstrap.session().sessionFile()).exists();
                assertThat(bootstrap.resources()).isNotNull();
                assertThat(bootstrap.toolRegistry().tools()).isNotEmpty();
                assertThat(bootstrap.modelSelection()).isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM));
                assertThat(bootstrap.systemPrompt()).isNotNull();
            });
    }

    @Test
    void appEntryRunsInitialPromptAfterBootstrap() {
        RecordingCore core = new RecordingCore();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(AgentCorePort.class, () -> core)
            .run(context -> {
                AppEntry appEntry = context.getBean(AppEntry.class);
                appEntry.start(new BootstrapRequest(
                    tempDir,
                    List.of(),
                    Optional.of("session-app-entry"),
                    Optional.of("hello")
                ));

                assertThat(core.request.get()).isNotNull();
                assertThat(core.request.get().sessionId()).isEqualTo("session-app-entry");
                assertThat(core.request.get().userInput()).isEqualTo("hello");
                assertThat(core.request.get().abortSignal().aborted()).isFalse();
                assertThat(core.request.get().maxToolRounds()).isEqualTo(TurnRequest.DEFAULT_MAX_TOOL_ROUNDS);
                assertThat(tempDir.resolve(".lypi/sessions/session-app-entry.jsonl")).exists();
            });
    }

    @Test
    void appEntryLaunchesTuiWhenSelected() {
        RecordingCore core = new RecordingCore();
        RecordingTransportLauncher launcher = new RecordingTransportLauncher("tui");

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.transport=tui"
            )
            .withBean(AgentCorePort.class, () -> core)
            .withBean(TransportLauncher.class, () -> launcher)
            .run(context -> {
                AppEntry appEntry = context.getBean(AppEntry.class);
                appEntry.start(new BootstrapRequest(
                    tempDir,
                    List.of(),
                    Optional.of("session-tui"),
                    Optional.empty()
                ));

                assertThat(launcher.state.get()).isNotNull();
                assertThat(launcher.state.get().sessionId()).isEqualTo("session-tui");
                assertThat(launcher.core.get()).isSameAs(core);
                assertThat(launcher.events.get()).isSameAs(context.getBean(EventBus.class));
                assertThat(core.request.get()).isNull();
            });
    }

    @Test
    void createsApplicationRunnerThatStartsAppEntry() throws Exception {
        RecordingAppEntry appEntry = new RecordingAppEntry();

        runtimeConfiguration()
            .withPropertyValues(
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-runner",
                "lypi.runtime.initial-prompt=from runner"
            )
            .withBean(AppEntry.class, () -> appEntry)
            .run(context -> {
                assertThat(context).hasSingleBean(ApplicationRunner.class);

                context.getBean(ApplicationRunner.class).run(null);

                assertThat(appEntry.request.get()).isNotNull();
                assertThat(appEntry.request.get().cwd()).isEqualTo(tempDir.toAbsolutePath().normalize());
                assertThat(appEntry.request.get().sessionId()).contains("session-runner");
                assertThat(appEntry.request.get().initialPrompt()).contains("from runner");
            });
    }

    @Test
    void runtimeAndToolAutoConfigurationShareDefaultEventBusWithTransports() {
        RecordingTransport transport = new RecordingTransport("transport");
        List<EventEnvelope> receivedEvents = new ArrayList<>();

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues("lypi.runtime.cwd=" + tempDir)
            .withBean(TransportAdapter.class, () -> transport)
            .withBean(SessionRuntimeState.class, LyPiRuntimeAutoConfigurationTest::sessionState)
            .withBean(SecurityRuntimePort.class, () -> LyPiRuntimeAutoConfigurationTest::allowAllSecurity)
            .withBean(PermissionPromptPort.class, () -> handle -> PermissionGateResult.allow())
            .run(context -> {
                EventBus eventBus = context.getBean(EventBus.class);
                assertThat(eventBus).isInstanceOf(InMemoryEventBus.class);
                assertThat(transport.events.get()).isSameAs(eventBus);

                eventBus.subscribe(new EventFilter(java.util.Optional.empty(), java.util.Optional.empty()), receivedEvents::add);
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new SuccessTool());
                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-probe", Map.of("path", "pom.xml"), "msg_1")),
                    contextSnapshot()
                ).getFirst();

                assertThat(result.isError()).isFalse();
                assertThat(receivedEvents.stream()
                    .map(EventEnvelope::event)
                    .filter(event -> event instanceof PermissionRequestEvent || event instanceof PermissionDecisionEvent)
                    .map(cn.lypi.contracts.event.AgentEvent::getClass))
                    .containsExactly(PermissionRequestEvent.class, PermissionDecisionEvent.class);
            });
    }

    private static PermissionDecision allowAllSecurity(ToolUseRequest request, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            java.util.Optional.empty(),
            Map.of()
        );
    }

    private static ContextSnapshot contextSnapshot() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }

    private static SessionRuntimeState sessionState() {
        return new SessionRuntimeState(
            "session-1",
            Path.of(".").toAbsolutePath().normalize(),
            "leaf-1",
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO),
            false
        );
    }

    private static final class RecordingTransport implements TransportAdapter {
        private final String name;
        private final AtomicReference<EventBus> events = new AtomicReference<>();
        private final AtomicReference<SessionRuntimeState> state = new AtomicReference<>();

        private RecordingTransport(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void attach(EventBus events, SessionRuntimeState state) {
            this.events.set(events);
            this.state.set(state);
        }
    }

    private static final class RecordingEventBus implements EventBus {
        private final List<cn.lypi.contracts.event.AgentEvent> events = new ArrayList<>();

        @Override
        public void publish(cn.lypi.contracts.event.AgentEvent event) {
            events.add(event);
        }

        @Override
        public cn.lypi.contracts.event.EventSubscription subscribe(
            cn.lypi.contracts.event.EventFilter filter,
            cn.lypi.contracts.event.EventConsumer consumer
        ) {
            return () -> {
            };
        }
    }

    private static final class RecordingCore implements AgentCorePort {
        private final AtomicReference<TurnRequest> request = new AtomicReference<>();

        @Override
        public TurnState execute(TurnRequest request) {
            this.request.set(request);
            return new TurnState("turn-1", request.sessionId(), null, List.of(), 0, TurnStatus.COMPLETED);
        }
    }

    private static final class RecordingTransportLauncher implements TransportLauncher {
        private final String name;
        private final AtomicReference<SessionRuntimeState> state = new AtomicReference<>();
        private final AtomicReference<AgentCorePort> core = new AtomicReference<>();
        private final AtomicReference<EventBus> events = new AtomicReference<>();

        private RecordingTransportLauncher(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void launch(SessionRuntimeState state, AgentCorePort core, EventBus events) {
            this.state.set(state);
            this.core.set(core);
            this.events.set(events);
        }
    }

    private static final class RecordingAppEntry implements AppEntry {
        private final AtomicReference<BootstrapRequest> request = new AtomicReference<>();

        @Override
        public void start(BootstrapRequest request) {
            this.request.set(request);
        }
    }

    private static final class SuccessTool implements Tool<Map<String, Object>, String> {
        @Override
        public String name() {
            return "ask-probe";
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of());
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "需要确认",
                java.util.Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            return new ToolResult<>("content", false, List.of(serializeForContext("content")), java.util.Optional.empty());
        }

        @Override
        public cn.lypi.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lypi.contracts.tool.InterruptBehavior.CANCEL;
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isConcurrencySafe(Map<String, Object> input) {
            return true;
        }

        @Override
        public boolean isDestructive(Map<String, Object> input) {
            return false;
        }

        @Override
        public int maxResultSize() {
            return 4096;
        }

        @Override
        public String renderForUser(Map<String, Object> input) {
            return "ask-probe " + input;
        }

        @Override
        public cn.lypi.contracts.context.AgentMessage serializeForContext(String output) {
            return new cn.lypi.contracts.context.AgentMessage(
                "msg_tool_result",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("toolu_1", output, false)),
                Instant.EPOCH,
                java.util.Optional.empty(),
                java.util.Optional.empty()
            );
        }
    }
}
