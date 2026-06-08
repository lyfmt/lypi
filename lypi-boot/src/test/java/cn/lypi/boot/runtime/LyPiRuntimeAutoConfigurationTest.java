package cn.lypi.boot.runtime;

import cn.lypi.boot.tool.LyPiToolAutoConfiguration;
import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiRuntimeAutoConfigurationTest {
    @TempDir
    Path tempDir;

    @Test
    void createsDefaultInMemoryEventBusWhenMissing() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(EventBus.class);
                assertThat(context.getBean(EventBus.class)).isInstanceOf(InMemoryEventBus.class);
            });
    }

    @Test
    void keepsUserProvidedEventBus() {
        EventBus customEventBus = new RecordingEventBus();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
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

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
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

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
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
    void runtimeAndToolAutoConfigurationShareDefaultEventBusWithTransports() {
        RecordingTransport transport = new RecordingTransport("transport");
        List<EventEnvelope> receivedEvents = new ArrayList<>();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class, LyPiToolAutoConfiguration.class)
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

    @Test
    void createsDefaultSubagentRuntimeBeansWithConservativeDeliveryGuard() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SessionManagerFactoryPort.class);
                assertThat(context).hasSingleBean(ChildSessionPort.class);
                assertThat(context).hasSingleBean(SessionManagerPort.class);
                assertThat(context).hasSingleBean(MailboxPort.class);
                assertThat(context).hasSingleBean(SubagentProcessRunner.class);
                assertThat(context).hasSingleBean(AgentCenterPort.class);
                assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(null)).isFalse();
            });
    }

    @Test
    void createsAgentCoreFactoryWhenRequiredRuntimePortsExist() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(AiProviderRuntimePort.class, () -> (snapshot, signal) -> {
                throw new UnsupportedOperationException("not used");
            })
            .withBean(ToolRuntimePort.class, NoopToolRuntime::new)
            .withBean(SecurityRuntimePort.class, () -> LyPiRuntimeAutoConfigurationTest::allowAllSecurity)
            .withBean(ResourceRuntimePort.class, NoopResourceRuntime::new)
            .withBean(CompactionSummarizer.class, () -> request -> new CompactSummaryResult(
                "summary",
                new TokenUsage(0, 0, 0, 0)
            ))
            .run(context -> assertThat(context).hasSingleBean(AgentCoreFactoryPort.class));
    }

    @Test
    void defaultDeliveryGuardAllowsSameIdleSessionWhenRuntimeStateExists() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .withBean(SessionManagerPort.class, () -> new BranchingSessionManager(true))
            .run(context -> {
                MailboxDeliveryGuard guard = context.getBean(MailboxDeliveryGuard.class);

                assertThat(guard.canDeliver(mail("ses_parent"))).isTrue();
                assertThat(guard.canDeliver(mail("ses_other"))).isFalse();
            });
    }

    @Test
    void defaultDeliveryGuardKeepsMailboxPendingWhenCurrentBranchMovedAwayFromSpawnEntry() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .withBean(SessionManagerPort.class, () -> new BranchingSessionManager(false))
            .run(context -> assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(mail("ses_parent"))).isFalse());
    }

    @Test
    void defaultDeliveryGuardKeepsMailboxPendingWhenRuntimeStateHasRunningTool() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", true))
            .run(context -> assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(mail("ses_parent"))).isFalse());
    }

    @Test
    void bindsSubagentCommandToRunnerAndAgentCenter() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withPropertyValues(
                "lypi.subagent.command[0]=python3",
                "lypi.subagent.command[1]=-c",
                "lypi.subagent.command[2]=import json, sys; data=json.load(sys.stdin); print(json.dumps({'childSessionId':data['childSessionId'],'status':'SUCCEEDED','summary':'ok','finalEntryId':'msg_final'}))"
            )
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);
                sessionManager.openOrCreate("ses_parent");
                sessionManager.appendMessage(new AgentMessage(
                    "msg_parent",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new cn.lypi.contracts.context.TextContentBlock("parent", Map.of())),
                    Instant.EPOCH,
                    java.util.Optional.empty(),
                    java.util.Optional.empty()
                ));
                AgentCenterPort agentCenter = context.getBean(AgentCenterPort.class);

                SubagentSpawnResult result = agentCenter.spawn(new SubagentSpawnRequest(
                    "ses_parent",
                    sessionManager.currentView().leafId(),
                    "执行检查",
                    tempDir,
                    List.of(),
                    PermissionMode.DEFAULT_EXECUTE,
                    30,
                    java.util.Optional.empty(),
                    java.util.Optional.empty()
                ));

                assertThat(result.status()).isEqualTo(SubagentRunStatus.STARTED);
                assertThat(result.agentId()).isNotBlank();
            });
    }

    @Test
    void keepsUserProvidedSessionManagerForMailboxAndAgentCenter() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        sessionManager.openOrCreate("ses_custom");

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .run(context -> assertThat(context.getBean(SessionManagerPort.class)).isSameAs(sessionManager));
    }

    @Test
    void keepsUserProvidedMailboxPortWithoutRequiringDefaultAgentCenter() {
        MailboxPort mailbox = new NoopMailbox();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(MailboxPort.class, () -> mailbox)
            .run(context -> {
                assertThat(context).hasSingleBean(MailboxPort.class);
                assertThat(context.getBean(MailboxPort.class)).isSameAs(mailbox);
                assertThat(context).doesNotHaveBean(AgentCenterPort.class);
            });
    }

    @Test
    void registersMailboxSlashCommandHandlerWithRuntimeStateSession() {
        NoopMailbox mailbox = new NoopMailbox();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(MailboxPort.class, () -> mailbox)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .run(context -> {
                MailboxSlashCommandHandler handler = context.getBean(MailboxSlashCommandHandler.class);

                handler.handle(Map.of("action", "list"));

                assertThat(handler.command().name()).isEqualTo("mailbox");
                assertThat(mailbox.readSessionId).isEqualTo("ses_parent");
                assertThat(mailbox.readStatuses).containsExactly(MailboxStatus.PENDING);
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
        return sessionState("session-1", false);
    }

    private static SessionRuntimeState sessionState(String sessionId, boolean hasInterruptibleTool) {
        return new SessionRuntimeState(
            sessionId,
            Path.of(".").toAbsolutePath().normalize(),
            "leaf-1",
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO),
            hasInterruptibleTool
        );
    }

    private static MailboxMessage mail(String parentSessionId) {
        return new MailboxMessage(
            "mail_1",
            "agent_1",
            "ses_child",
            parentSessionId,
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", java.util.Optional.empty()),
            MailboxStatus.PENDING,
            Instant.EPOCH,
            Instant.EPOCH
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

    private static final class NoopMailbox implements MailboxPort {
        private String readSessionId;
        private java.util.Set<MailboxStatus> readStatuses;

        @Override
        public List<cn.lypi.contracts.subagent.MailboxMessage> read(
            String sessionId,
            java.util.Set<cn.lypi.contracts.subagent.MailboxStatus> statuses
        ) {
            readSessionId = sessionId;
            readStatuses = statuses;
            return List.of();
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult accept(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult stash(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult discard(String sessionId, String mailId) {
            return cn.lypi.contracts.subagent.MailboxCommandResult.failure("not used");
        }
    }

    private static final class NoopResourceRuntime implements ResourceRuntimePort {
        @Override
        public ResourceSnapshot load(Path cwd) {
            return new ResourceSnapshot(List.of(), List.of(), new SkillIndex(List.of(), List.of()), List.of(), List.of(), List.of());
        }

        @Override
        public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
            return new SystemPrompt("system", List.of(), "hash");
        }
    }

    private static final class NoopToolRuntime implements ToolRuntimePort {
        @Override
        public void register(Tool<?, ?> tool) {
        }

        @Override
        public java.util.Optional<Tool<?, ?>> resolve(String nameOrAlias) {
            return java.util.Optional.empty();
        }

        @Override
        public ToolRegistrySnapshot snapshot() {
            return new ToolRegistrySnapshot(List.of());
        }

        @Override
        public Path cwd() {
            return Path.of(".").toAbsolutePath().normalize();
        }

        @Override
        public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
            return List.of();
        }
    }

    private static final class BranchingSessionManager implements SessionManagerPort {
        private final boolean branchContainsSpawnEntry;

        private BranchingSessionManager(boolean branchContainsSpawnEntry) {
            this.branchContainsSpawnEntry = branchContainsSpawnEntry;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            return new SessionHandle(sessionId, null, "leaf-1", Map.of());
        }

        @Override
        public SessionHandle append(SessionEntry entry) {
            return new SessionHandle("ses_parent", null, entry.id(), Map.of());
        }

        @Override
        public SessionHandle switchLeaf(String leafId) {
            return new SessionHandle("ses_parent", null, leafId, Map.of());
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            if (!branchContainsSpawnEntry) {
                return List.of(new SessionInfoEntry("entry_other", null, Map.of(), Instant.EPOCH));
            }
            return List.of(
                new SessionInfoEntry("entry_spawn", null, Map.of(), Instant.EPOCH),
                new SessionInfoEntry(leafId, "entry_spawn", Map.of(), Instant.EPOCH)
            );
        }

        @Override
        public SessionView currentView() {
            return new SessionView("ses_parent", "leaf-1");
        }

        @Override
        public SessionView view(String leafId) {
            return new SessionView("ses_parent", leafId);
        }

        @Override
        public List<AgentMessage> transcript(String leafId) {
            return List.of();
        }

        @Override
        public SessionContext context(String leafId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public SessionHandle appendMessage(AgentMessage message) {
            return new SessionHandle("ses_parent", null, message.id(), Map.of());
        }

        @Override
        public SessionHandle fork(ForkRequest request) {
            throw new UnsupportedOperationException();
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
