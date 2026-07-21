package cn.lypi.boot.runtime;

import cn.lypi.agent.compact.CompactSummaryResult;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextBuildRequest;
import cn.lypi.boot.BootstrapService;
import cn.lypi.boot.ai.LyPiAiAutoConfiguration;
import cn.lypi.boot.tool.LyPiToolAutoConfiguration;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.agent.TurnStatus;
import cn.lypi.contracts.bootstrap.BootstrapContext;
import cn.lypi.contracts.bootstrap.BootstrapRequest;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.EventFilter;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.model.AssistantDone;
import cn.lypi.contracts.model.AssistantEventStream;
import cn.lypi.contracts.model.AssistantStart;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.AssistantStreamResult;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.model.ToolCallDelta;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.CompactStateBackfillPort;
import cn.lypi.contracts.runtime.CompactionRequest;
import cn.lypi.contracts.runtime.CompactionResult;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.LyPiRuntime;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.tui.BranchSummaryOffer;
import cn.lypi.contracts.session.ChildSessionRequest;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRunner;
import cn.lypi.runtime.memory.MemoryConsolidationTrigger;
import cn.lypi.runtime.memory.MemoryConsolidationTurnEndListener;
import cn.lypi.runtime.subagent.AgentCompactStateBackfill;
import cn.lypi.runtime.subagent.ChildAgentSnapshotProvider;
import cn.lypi.runtime.subagent.DefaultAgentRegistry;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.RunningAgentSnapshotProvider;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.transport.tui.AgentSlashCommandHandler;
import cn.lypi.transport.tui.JLineTuiTransportFactory;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import cn.lypi.tool.FilePermissionAmendmentStore;
import cn.lypi.tool.PermissionGateResult;
import cn.lypi.tool.PermissionPromptPort;
import cn.lypi.tool.MemoryConsolidationToolRuntime;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

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
    void sessionManagerUsesCodexStylePermissionsConfigAsDefaultRuntimeState() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.permissions.default-permissions=:read-only",
                "lypi.permissions.approval-policy.mode=granular",
                "lypi.permissions.approval-policy.granular.sandbox-approval=on_request",
                "lypi.permissions.approval-policy.granular.rules=never",
                "lypi.permissions.approval-policy.granular.skill-approval=on_request",
                "lypi.permissions.approval-policy.granular.request-permissions=on_request",
                "lypi.permissions.approval-policy.granular.mcp-elicitations=never"
            )
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);

                SessionHandle handle = sessionManager.openOrCreate("ses_permissions_config");
                SessionContext sessionContext = sessionManager.context(handle.leafId());

                assertThat(sessionContext.permissionRuntimeState().activePermissionProfile().id())
                    .isEqualTo(":read-only");
                assertThat(sessionContext.permissionRuntimeState().approvalPolicy().mode())
                    .isEqualTo(ApprovalMode.GRANULAR);
                assertThat(sessionContext.permissionRuntimeState().approvalPolicy().granularApprovalPolicy().orElseThrow().rules())
                    .isEqualTo(ApprovalMode.NEVER);
                assertThat(sessionContext.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
            });
    }

    @Test
    void defaultRuntimeStateUsesPermissionModeSandboxProfile() {
        runtimeAutoConfigurations()
            .withPropertyValues("lypi.runtime.permission-mode=bypass")
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);

                SessionHandle handle = sessionManager.openOrCreate("ses_bypass_profile");
                SessionContext sessionContext = sessionManager.context(handle.leafId());

                assertThat(sessionContext.permissionRuntimeState().activePermissionProfile().id())
                    .isEqualTo(":danger-full-access");
                assertThat(sessionContext.permissionRuntimeState().permissionProfile().kind())
                    .isEqualTo(cn.lypi.contracts.security.PermissionProfile.Kind.DISABLED);
            });
    }

    @Test
    void explicitDefaultPermissionsOverridesPermissionModeSandboxProfile() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.runtime.permission-mode=bypass",
                "lypi.permissions.default-permissions=:workspace"
            )
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);

                SessionHandle handle = sessionManager.openOrCreate("ses_explicit_workspace_profile");
                SessionContext sessionContext = sessionManager.context(handle.leafId());

                assertThat(sessionContext.permissionRuntimeState().activePermissionProfile().id())
                    .isEqualTo(":workspace");
                assertThat(sessionContext.permissionRuntimeState().permissionProfile().kind())
                    .isEqualTo(cn.lypi.contracts.security.PermissionProfile.Kind.MANAGED);
            });
    }

    @Test
    void sessionManagerStoresCompiledCustomPermissionProfileInRuntimeState() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.permissions.default-permissions=dev",
                "lypi.permissions.profiles.dev.extends-profile=:workspace",
                "lypi.permissions.profiles.dev.file-system.kind=restricted",
                "lypi.permissions.profiles.dev.file-system.entries[0].path.kind=special",
                "lypi.permissions.profiles.dev.file-system.entries[0].path.value=:root",
                "lypi.permissions.profiles.dev.file-system.entries[0].access=read",
                "lypi.permissions.profiles.dev.file-system.entries[1].path.kind=exact_path",
                "lypi.permissions.profiles.dev.file-system.entries[1].path.value=/tmp/lypi-cache",
                "lypi.permissions.profiles.dev.file-system.entries[1].access=write"
            )
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);

                SessionHandle handle = sessionManager.openOrCreate("ses_custom_profile");
                SessionContext sessionContext = sessionManager.context(handle.leafId());

                assertThat(sessionContext.permissionRuntimeState().activePermissionProfile().id())
                    .isEqualTo("dev");
                assertThat(sessionContext.permissionRuntimeState().permissionProfile().fileSystem().kind())
                    .isEqualTo(FileSystemPolicyKind.RESTRICTED);
                assertThat(sessionContext.permissionRuntimeState().permissionProfile().fileSystem().entries())
                    .anySatisfy(entry -> {
                        assertThat(entry.path().kind()).isEqualTo(FileSystemPath.Kind.EXACT_PATH);
                        assertThat(entry.path().value()).contains("/tmp/lypi-cache");
                        assertThat(entry.access()).isEqualTo(FileSystemAccessMode.WRITE);
                    });
            });
    }

    @Test
    void sessionRuntimeStateAndSandboxUseSameLegacyHostNetworkProfileSelection() {
        runtimeAutoConfigurations()
            .withPropertyValues("lypi.tool.sandbox.network-mode=host")
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);
                SandboxPolicyResolver resolver = context.getBean(SandboxPolicyResolver.class);

                SessionHandle handle = sessionManager.openOrCreate("ses_legacy_host_network");
                SessionContext sessionContext = sessionManager.context(handle.leafId());

                assertThat(sessionContext.permissionRuntimeState().activePermissionProfile().id())
                    .isEqualTo("legacy-workspace-network");
                assertThat(sessionContext.permissionRuntimeState().permissionProfile().network().mode())
                    .isEqualTo(NetworkPolicyMode.ENABLED);
                assertThat(resolver.resolve(tempDir, tempDir).networkMode()).isEqualTo(NetworkMode.HOST);
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
    void contextAssemblerUsesConfiguredModelContextWindowForCompactThreshold() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        sessionManager.openOrCreate("ses_budget");
        SessionHandle parent = sessionManager.appendMessage(new AgentMessage(
            "msg-user",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock("hello")),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        ));
        sessionManager.append(new ModelChangeEntry(
            "entry-model",
            parent.leafId(),
            new ModelSelection("fixture", "fixture-model", ThinkingLevel.MEDIUM),
            "test",
            Instant.EPOCH
        ));

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.providers.fixture.enabled=true",
                "lypi.ai.providers.fixture.api-style=openai_compatible",
                "lypi.ai.providers.fixture.base-url=https://api.fixture.example/v1",
                "lypi.ai.providers.fixture.models[0].model-id=fixture-model",
                "lypi.ai.providers.fixture.models[0].context-window=64000",
                "lypi.ai.providers.fixture.models[0].max-output-tokens=8192"
            )
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .run(context -> {
                ContextAssembler assembler = context.getBean(ContextAssembler.class);

                ContextBudget budget = assembler.build(new ContextBuildRequest(
                    "ses_budget",
                    Optional.of("entry-model"),
                    tempDir,
                    true
                )).snapshot().budget();

                assertThat(budget.effectiveContextWindow()).isEqualTo(64_000);
                assertThat(budget.autoCompactThreshold()).isEqualTo(51_200);
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
                assertThat(context.getBean(SecurityRuntimePort.class)).isInstanceOf(AmendmentAwareSecurityRuntime.class);
                assertThat(context).hasSingleBean(SessionManagerPort.class);
                assertThat(context).hasSingleBean(ResourceRuntimePort.class);
                assertThat(context).hasSingleBean(AiProviderRuntimePort.class);
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                assertThat(context).hasSingleBean(CompactionRuntimePort.class);
                assertThat(context).hasSingleBean(AgentCorePort.class);
                assertThat(context).hasSingleBean(LyPiRuntime.class);

                LyPiRuntime runtime = context.getBean(LyPiRuntime.class);
                assertThat(runtime.sessionManager()).isSameAs(context.getBean(SessionManagerPort.class));
                assertThat(runtime.agentCore()).isSameAs(context.getBean(AgentCorePort.class));
                assertThat(runtime.aiProvider()).isSameAs(context.getBean(AiProviderRuntimePort.class));
                assertThat(runtime.toolRuntime()).isSameAs(context.getBean(ToolRuntimePort.class));
                assertThat(runtime.securityRuntime()).isSameAs(context.getBean(SecurityRuntimePort.class));
                assertThat(runtime.resourceRuntime()).isSameAs(context.getBean(ResourceRuntimePort.class));
                assertThat(runtime.compactionRuntime()).isSameAs(context.getBean(CompactionRuntimePort.class));
            });
    }

    @Test
    void defaultSecurityRuntimeLoadsExecPolicyPrefixRulesFromRuntimeCwd() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve("rules"));
        java.nio.file.Files.writeString(
            tempDir.resolve("rules/default.rules"),
            "prefix_rule(pattern=[\"go\", \"test\"], decision=\"allow\")\n"
        );

        runtimeConfiguration()
            .run(context -> {
                SecurityRuntimePort security = context.getBean(SecurityRuntimePort.class);

                PermissionDecision decision = security.decide(
                    new ToolUseRequest("toolu_1", "bash", Map.of("command", "go test ./..."), "msg_1"),
                    new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("permissionMode", PermissionMode.DEFAULT_EXECUTE))
                );

                assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
            });
    }

    @Test
    void defaultSecurityRuntimeLoadsPermissionAmendmentsFromRuntimeCwd() {
        FilePermissionAmendmentStore store = new FilePermissionAmendmentStore(tempDir);
        store.appendPermissionUpdate(
            new PermissionUpdate(
                PermissionRuleSource.USER,
                new PermissionRule(
                    PermissionRuleSource.USER,
                    PermissionBehavior.ALLOW,
                    new PermissionRuleValue("bash", "prefix:cargo build"),
                    "允许 Bash prefix: cargo build"
                )
            ),
            PermissionGrantScope.SESSION,
            "ses_1"
        );

        runtimeConfiguration()
            .run(context -> {
                SecurityRuntimePort security = context.getBean(SecurityRuntimePort.class);

                PermissionDecision decision = security.decide(
                    new ToolUseRequest("toolu_1", "bash", Map.of("command", "cargo build --workspace"), "msg_1"),
                    new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("permissionMode", PermissionMode.DEFAULT_EXECUTE))
                );

                assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
                PermissionDecision otherSessionDecision = security.decide(
                    new ToolUseRequest("toolu_2", "bash", Map.of("command", "cargo build --workspace"), "msg_1"),
                    new ToolUseContext("ses_2", "msg_1", tempDir, Map.of("permissionMode", PermissionMode.DEFAULT_EXECUTE))
                );
                assertThat(otherSessionDecision.behavior()).isEqualTo(PermissionBehavior.ASK);
            });
    }

    @Test
    void compactionRuntimeUsesManualPlannerIndependentOfAutoThreshold() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(CompactionSummarizer.class, () -> request -> new CompactSummaryResult(
                "manual summary",
                new cn.lypi.contracts.model.TokenUsage(1, 1, 0, 2)
            ))
            .run(context -> {
                SessionManagerPort session = context.getBean(SessionManagerPort.class);
                context.getBean(ToolRuntimePort.class).register(new McpSnapshotTool());
                session.openOrCreate("session-manual-compact");
                String rootLeaf = session.currentView().leafId();
                session.append(messageEntry("entry-user-1", rootLeaf, MessageRole.USER, "old user"));
                session.append(messageEntry("entry-assistant-1", "entry-user-1", MessageRole.ASSISTANT, "old assistant"));
                session.append(messageEntry("entry-user-2", "entry-assistant-1", MessageRole.USER, "recent user"));

                CompactionResult result = context.getBean(CompactionRuntimePort.class).compact(new CompactionRequest(
                    "session-manual-compact",
                    Optional.of("entry-user-2"),
                    tempDir,
                    () -> false
                ));

                assertThat(result.compacted()).isTrue();
                assertThat(session.branch(session.currentView().leafId()))
                    .filteredOn(cn.lypi.contracts.session.CompactionEntry.class::isInstance)
                    .singleElement()
                    .satisfies(entry -> assertThat(((cn.lypi.contracts.session.CompactionEntry) entry).kind())
                        .isEqualTo(cn.lypi.contracts.session.CompactionKind.MANUAL));
                assertThat(session.context(session.currentView().leafId()).messages())
                    .flatExtracting(AgentMessage::content)
                    .extracting(cn.lypi.contracts.context.ContentBlock::text)
                    .anySatisfy(text -> assertThat(text).contains("mcp__filesystem__read_file"));
            });
    }

    @Test
    void defaultRuntimeExecutesToolCallsWithConfiguredRuntimeCwd() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("target-file.txt"), "from configured cwd");

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(AiProviderRuntimePort.class, () -> new ScriptedAiProvider(List.of(
                List.of(
                    new AssistantStart("msg-tool-call"),
                    new ToolCallDelta("toolu-1", "read", Map.of("path", "target-file.txt"), true),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ),
                List.of(
                    new AssistantStart("msg-final"),
                    new AssistantDone(Optional.empty(), Optional.of("end_turn"))
                )
            )))
            .run(context -> {
                TurnState state = context.getBean(AgentCorePort.class).execute(new TurnRequest(
                    "session-runtime-cwd",
                    "read file",
                    Optional.empty(),
                    () -> false
                ));

                List<String> toolTexts = state.newMessages().stream()
                    .flatMap(message -> message.content().stream())
                    .filter(ToolResultContentBlock.class::isInstance)
                    .map(ToolResultContentBlock.class::cast)
                    .map(ToolResultContentBlock::text)
                    .toList();
                assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
                assertThat(toolTexts).anySatisfy(text -> assertThat(text).contains("from configured cwd"));
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
                assertThat(bootstrap.systemPrompt().content())
                    .contains("## Permissions")
                    .contains("approval policy: ON_REQUEST")
                    .contains("active sandbox profile: :workspace")
                    .contains("request_permissions")
                    .contains("sandboxPermissions=requireEscalated")
                    .contains("sandboxPermissions=withAdditionalPermissions");
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
                assertThat(tempDir.resolve(".ly-pi/sessions/session-app-entry.jsonl")).exists();
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
    void appEntryUsesTemporaryUuidSessionWhenSessionIdIsNotConfigured() {
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
                    Optional.empty(),
                    Optional.empty()
                ));

                assertThat(launcher.state.get()).isNotNull();
                assertThat(launcher.state.get().sessionId()).matches("session_[0-9a-f]{32}");
                assertThat(launcher.state.get().sessionId()).isNotEqualTo("default");
                assertThat(core.request.get()).isNull();
                assertThat(tempDir.resolve(".ly-pi/sessions")).doesNotExist();
            });
    }

    @Test
    void explicitDefaultSessionIdStillCreatesAndReusesDefaultSession() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=default"
            )
            .run(context -> {
                SessionRuntimeState state = context.getBean(SessionRuntimeState.class);

                assertThat(state.sessionId()).isEqualTo("default");
                assertThat(tempDir.resolve(".ly-pi/sessions/default.jsonl")).exists();
            });
    }

    @Test
    void appEntryLaunchesTuiWithSessionTreeProjectedRuntimeState() {
        RecordingCore core = new RecordingCore();
        RecordingTransportLauncher launcher = new RecordingTransportLauncher("tui");

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.thinking-level=medium",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.transport=tui"
            )
            .withBean(AgentCorePort.class, () -> core)
            .withBean(TransportLauncher.class, () -> launcher)
            .run(context -> {
                SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);
                sessionManager.openOrCreate("session-tui-tree");
                sessionManager.append(new ThinkingChangeEntry(
                    "thinking-high",
                    null,
                    ThinkingLevel.HIGH,
                    "/thinking high",
                    Instant.parse("2026-06-11T00:00:00Z")
                ));

                AppEntry appEntry = context.getBean(AppEntry.class);
                appEntry.start(new BootstrapRequest(
                    tempDir,
                    List.of(),
                    Optional.of("session-tui-tree"),
                    Optional.empty()
                ));

                assertThat(launcher.state.get()).isNotNull();
                assertThat(launcher.state.get().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
                assertThat(launcher.state.get().model())
                    .isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.HIGH));
                assertThat(launcher.state.get().currentBranchLeafId()).isEqualTo("thinking-high");
            });
    }

    @Test
    void createsDefaultJLineTuiTransportLauncherWithRuntimePorts() {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .run(context -> {
                assertThat(context).hasBean("jLineTuiTransportLauncher");
                TransportLauncher launcher = context.getBean("jLineTuiTransportLauncher", TransportLauncher.class);

                assertThat(launcher.name()).isEqualTo("tui");
                assertThat(context).hasSingleBean(CompactionRuntimePort.class);
                assertThat(context).hasSingleBean(JLineTuiTransportFactory.class);
                assertThat(launcher).hasFieldOrPropertyWithValue("factory", context.getBean(JLineTuiTransportFactory.class));
            });
    }

    @Test
    void createsResumeSessionControllerBackedBySessionStorageAndManager() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        RecordingEventBus events = new RecordingEventBus();
        sessionManager.openOrCreate("ses_old");
        SessionHandle userLeaf = sessionManager.appendMessage(new AgentMessage(
            "msg_user",
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new cn.lypi.contracts.context.TextContentBlock("old prompt")),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        ));

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .withBean(EventBus.class, () -> events)
            .run(context -> {
                assertThat(context).hasSingleBean(ResumeSessionController.class);
                ResumeSessionController controller = context.getBean(ResumeSessionController.class);

                List<SessionResumeInfo> sessions = controller.sessions();
                assertThat(sessions).extracting(SessionResumeInfo::sessionId).contains("ses_old");

                SessionBranchTreeView tree = controller.tree("ses_old");
                assertThat(tree.sessionId()).isEqualTo("ses_old");
                assertThat(tree.roots()).isNotEmpty();

                SessionRuntimeState resumed = controller.resume("ses_old", userLeaf.leafId());

                assertThat(sessionManager.currentView().sessionId()).isEqualTo("ses_old");
                assertThat(sessionManager.currentView().leafId()).isEqualTo(userLeaf.leafId());
                assertThat(resumed.sessionId()).isEqualTo("ses_old");
                assertThat(resumed.currentBranchLeafId()).isEqualTo(userLeaf.leafId());
                assertThat(resumed.transcript())
                    .extracting(AgentMessage::id)
                    .containsExactly("msg_user");
                assertThat(events.events).hasAtLeastOneElementOfType(SessionStateEvent.class);
                SessionStateEvent state = (SessionStateEvent) events.events.getLast();
                assertThat(state.sessionId()).isEqualTo("ses_old");
                assertThat(state.leafId()).isEqualTo(userLeaf.leafId());
            });
    }

    @Test
    void resumeControllerOffersBranchSummaryOnlyForSameSessionSummarizableOldSuffix() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        RecordingEventBus events = new RecordingEventBus();
        sessionManager.openOrCreate("ses_old");
        SessionHandle root = sessionManager.appendMessage(userMessage("msg_root", "root"));
        SessionHandle oldMessage = sessionManager.appendMessage(assistantMessage("msg_old", "old branch"));
        SessionHandle oldLeaf = sessionManager.append(new SessionInfoEntry(
            "entry_old_info",
            oldMessage.leafId(),
            Map.of("ignored", true),
            Instant.EPOCH
        ));
        SessionHandle target = sessionManager.append(new cn.lypi.contracts.session.CustomMessageEntry(
            "entry_target",
            root.leafId(),
            "target branch",
            Instant.EPOCH
        ));
        sessionManager.switchLeaf(oldLeaf.leafId());
        ResumeSessionController controller = new DefaultResumeSessionController(
            tempDir,
            sessionManager,
            events,
            new cn.lypi.agent.branch.AiBranchSummarizer(
                new RecordingAiProvider(List.of()),
                new cn.lypi.agent.branch.BranchSummaryContextBuilder(new cn.lypi.agent.branch.BranchSummaryInstructionFactory())
            )
        );

        Optional<BranchSummaryOffer> offer = controller.branchSummaryOffer("ses_old", target.leafId());

        assertThat(offer).isPresent();
        assertThat(offer.orElseThrow().oldLeafId()).isEqualTo(oldLeaf.leafId());
        assertThat(offer.orElseThrow().targetLeafId()).isEqualTo(target.leafId());
        assertThat(offer.orElseThrow().entriesToSummarize()).isEqualTo(1);
        assertThat(controller.branchSummaryOffer("ses_other", target.leafId())).isEmpty();
        assertThat(controller.branchSummaryOffer("ses_old", oldLeaf.leafId())).isEmpty();
    }

    @Test
    void resumeWithBranchSummaryAppendsSummaryEntryAndReturnsSummaryLeafState() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        RecordingEventBus events = new RecordingEventBus();
        RecordingAiProvider provider = new RecordingAiProvider(List.of(
            new AssistantStart("msg-summary"),
            new cn.lypi.contracts.model.TextDelta("## Goal\nKeep abandoned branch."),
            new AssistantDone(Optional.of(new TokenUsage(4, 1, 3, 0)), Optional.of("stop"))
        ));
        sessionManager.openOrCreate("ses_old");
        SessionHandle root = sessionManager.appendMessage(userMessage("msg_root", "root"));
        SessionHandle oldLeaf = sessionManager.appendMessage(assistantMessage("msg_old", "old branch content"));
        SessionHandle target = sessionManager.append(new cn.lypi.contracts.session.CustomMessageEntry(
            "entry_target",
            root.leafId(),
            "target branch",
            Instant.EPOCH
        ));
        sessionManager.switchLeaf(oldLeaf.leafId());
        ResumeSessionController controller = new DefaultResumeSessionController(
            tempDir,
            sessionManager,
            events,
            new cn.lypi.agent.branch.AiBranchSummarizer(
                provider,
                new cn.lypi.agent.branch.BranchSummaryContextBuilder(new cn.lypi.agent.branch.BranchSummaryInstructionFactory())
            )
        );

        SessionRuntimeState state = controller.resumeWithBranchSummary("ses_old", target.leafId());

        assertThat(state.sessionId()).isEqualTo("ses_old");
        assertThat(state.currentBranchLeafId()).isNotEqualTo(target.leafId());
        assertThat(sessionManager.currentView().leafId()).isEqualTo(state.currentBranchLeafId());
        assertThat(sessionManager.branch(state.currentBranchLeafId()))
            .extracting(SessionEntry::id)
            .contains(root.leafId(), target.leafId(), state.currentBranchLeafId())
            .doesNotContain(oldLeaf.leafId());
        assertThat(state.transcript())
            .extracting(message -> message.content().getFirst().text())
            .contains("target branch")
            .anySatisfy(text -> assertThat(text).contains("Keep abandoned branch"));
        assertThat(provider.context.get().messages())
            .extracting(message -> message.content().getFirst().text())
            .contains("old branch content")
            .doesNotContain("target branch");
        assertThat(provider.context.get().systemPrompt()).isNotNull();
        assertThat(provider.context.get().systemPrompt().content()).isNotBlank();
        assertThat(events.events).hasAtLeastOneElementOfType(SessionStateEvent.class);
        SessionStateEvent event = (SessionStateEvent) events.events.getLast();
        assertThat(event.leafId()).isEqualTo(state.currentBranchLeafId());
    }

    @Test
    void createsNewSessionControllerThatSwitchesToEmptySessionWithDefaultSettings() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        RecordingEventBus events = new RecordingEventBus();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .withBean(EventBus.class, () -> events)
            .run(context -> {
                assertThat(context).hasSingleBean(NewSessionController.class);

                SessionRuntimeState state = context.getBean(NewSessionController.class).createNewSession();

                assertThat(state.sessionId()).isNotEqualTo("ses_old");
                assertThat(sessionManager.currentView().sessionId()).isEqualTo(state.sessionId());
                assertThat(state.transcript()).isEmpty();
                assertThat(state.model()).isEqualTo(new ModelSelection("default", "default", ThinkingLevel.MEDIUM));
                assertThat(state.thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
                assertThat(state.agentMode()).isEqualTo(AgentMode.EXECUTE);
                assertThat(state.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
                assertThat(events.events).hasAtLeastOneElementOfType(cn.lypi.contracts.event.SessionStartEvent.class);
                assertThat(events.events).hasAtLeastOneElementOfType(SessionStateEvent.class);
            });
    }

    @Test
    void blankInitialPromptFallsBackToTuiWhenSelected() {
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
                    Optional.of("session-blank-prompt"),
                    Optional.of("   ")
                ));

                assertThat(launcher.state.get()).isNotNull();
                assertThat(launcher.state.get().sessionId()).isEqualTo("session-blank-prompt");
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
    void applicationRunnerKeepsLyPiEntryWhenExternalRunnerExists() throws Exception {
        RecordingAppEntry appEntry = new RecordingAppEntry();
        ApplicationRunner externalRunner = args -> {
        };

        runtimeConfiguration()
            .withPropertyValues(
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-named-runner",
                "lypi.runtime.initial-prompt=from named runner"
            )
            .withBean(AppEntry.class, () -> appEntry)
            .withBean("externalRunner", ApplicationRunner.class, () -> externalRunner)
            .run(context -> {
                assertThat(context).hasBean("externalRunner");
                assertThat(context).hasBean("lyPiApplicationRunner");

                context.getBean("lyPiApplicationRunner", ApplicationRunner.class).run(null);

                assertThat(appEntry.request.get()).isNotNull();
                assertThat(appEntry.request.get().initialPrompt()).contains("from named runner");
            });
    }

    @Test
    void applicationRunnerBacksOffWhenLyPiRunnerBeanNameExists() {
        ApplicationRunner customLyPiRunner = args -> {
        };

        runtimeConfiguration()
            .withBean(AppEntry.class, RecordingAppEntry::new)
            .withBean("lyPiApplicationRunner", ApplicationRunner.class, () -> customLyPiRunner)
            .run(context -> assertThat(context.getBean("lyPiApplicationRunner", ApplicationRunner.class))
                .isSameAs(customLyPiRunner));
    }

    @Test
    void applicationRunnerSourceArgsBecomeHeadlessInitialPrompt() throws Exception {
        RecordingCore core = new RecordingCore();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-source-args",
                "lypi.runtime.transport=headless"
            )
            .withBean(AgentCorePort.class, () -> core)
            .run(context -> {
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments("hello", "from", "jar"));

                assertThat(core.request.get()).isNotNull();
                assertThat(core.request.get().sessionId()).isEqualTo("session-source-args");
                assertThat(core.request.get().userInput()).isEqualTo("hello from jar");
            });
    }

    @Test
    void applicationRunnerSkipsHeadlessSubagentProtocolMode() throws Exception {
        RecordingAppEntry appEntry = new RecordingAppEntry();

        runtimeConfiguration()
            .withPropertyValues(
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-subagent-protocol"
            )
            .withBean(AppEntry.class, () -> appEntry)
            .run(context -> {
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments("headless-subagent"));
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments("--lypi.headless.subagent=true"));

                assertThat(appEntry.request.get()).isNull();
            });
    }

    @Test
    void applicationRunnerUsesRuntimeDefaultModelForNewSession() throws Exception {
        RecordingAiProvider provider = new RecordingAiProvider(List.of(
            new AssistantStart("msg-final"),
            new AssistantDone(Optional.empty(), Optional.of("end_turn"))
        ));

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-runtime-default-model",
                "lypi.runtime.transport=headless"
            )
            .withBean(AiProviderRuntimePort.class, () -> provider)
            .run(context -> {
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments("hello"));

                assertThat(provider.context.get()).isNotNull();
                assertThat(provider.context.get().model())
                    .isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM));
            });
    }

    @Test
    void applicationRunnerIgnoresOptionArgsWhenBuildingCliPrompt() throws Exception {
        RecordingCore core = new RecordingCore();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-option-args",
                "lypi.runtime.transport=headless"
            )
            .withBean(AgentCorePort.class, () -> core)
            .run(context -> {
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments(
                        "--spring.profiles.active=test",
                        "--lypi.runtime.cwd=/ignored",
                        "hello"
                    ));

                assertThat(core.request.get()).isNotNull();
                assertThat(core.request.get().userInput()).isEqualTo("hello");
            });
    }

    @Test
    void applicationRunnerConfiguredInitialPromptWinsOverCliArgs() throws Exception {
        RecordingCore core = new RecordingCore();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=session-prompt-precedence",
                "lypi.runtime.initial-prompt=configured prompt",
                "lypi.runtime.transport=headless"
            )
            .withBean(AgentCorePort.class, () -> core)
            .run(context -> {
                context.getBean("lyPiApplicationRunner", ApplicationRunner.class)
                    .run(new DefaultApplicationArguments("cli", "prompt"));

                assertThat(core.request.get()).isNotNull();
                assertThat(core.request.get().userInput()).isEqualTo("configured prompt");
            });
    }

    @Test
    void tuiRuntimePermissionResponseUnlocksAskTool() {
        CountDownLatch requestPublished = new CountDownLatch(1);
        AtomicReference<PermissionRequestEvent> requestRef = new AtomicReference<>();

        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.transport=tui"
            )
            .run(context -> {
                EventBus eventBus = context.getBean(EventBus.class);
                eventBus.subscribe(new EventFilter(Optional.empty(), Optional.empty()), envelope -> {
                    if (envelope.event() instanceof PermissionRequestEvent request) {
                        requestRef.set(request);
                        requestPublished.countDown();
                    }
                });
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new SuccessTool());

                CompletableFuture<ToolResult<?>> resultFuture = CompletableFuture.supplyAsync(() -> runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-probe", Map.of("path", "pom.xml"), "msg_1")),
                    contextSnapshot()
                ).getFirst());

                assertThat(requestPublished.await(2, TimeUnit.SECONDS)).isTrue();
                PermissionRequestEvent request = requestRef.get();
                eventBus.publish(new PermissionResponseEvent(
                    request.sessionId(),
                    request.requestId(),
                    request.defaultOptionId(),
                    false,
                    Instant.now()
                ));

                ToolResult<?> result = resultFuture.get(2, TimeUnit.SECONDS);

                assertThat(result.isError()).isFalse();
            });
    }

    @Test
    void headlessRuntimeDeniesAskToolWithoutWaitingForPermissionResponse() throws Exception {
        runtimeAutoConfigurations()
            .withPropertyValues(
                "lypi.ai.default-provider=openai",
                "lypi.ai.default-model=gpt-5-mini",
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.transport=headless"
            )
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                runtime.register(new SuccessTool());

                CompletableFuture<ToolResult<?>> resultFuture = CompletableFuture.supplyAsync(() -> runtime.execute(
                    List.of(new ToolUseRequest("toolu_1", "ask-probe", Map.of("path", "pom.xml"), "msg_1")),
                    contextSnapshot()
                ).getFirst());

                ToolResult<?> result = resultFuture.get(500, TimeUnit.MILLISECONDS);

                assertThat(result.isError()).isTrue();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("权限请求未获允许");
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
                assertThat(context).hasSingleBean(AgentRegistryPort.class);
                assertThat(context).hasSingleBean(CompactStateBackfillPort.class);
                assertThat(context.getBean(AgentRegistryPort.class)).isInstanceOf(DefaultAgentRegistry.class);
                assertThat(context.getBean(CompactStateBackfillPort.class)).isInstanceOf(AgentCompactStateBackfill.class);
                assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(null)).isFalse();
            });
    }

    @Test
    void keepsUserProvidedCompactStateBackfillPort() {
        CompactStateBackfillPort backfill = request -> List.of();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(CompactStateBackfillPort.class, () -> backfill)
            .run(context -> {
                assertThat(context).hasSingleBean(CompactStateBackfillPort.class);
                assertThat(context.getBean(CompactStateBackfillPort.class)).isSameAs(backfill);
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
    void createsBackgroundMemoryConsolidationBeansWhenRuntimePortsExist() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues("lypi.runtime.cwd=" + tempDir)
            .withBean(AiProviderRuntimePort.class, () -> new ScriptedAiProvider(List.of(
                List.of(
                    new AssistantStart("msg-final"),
                    new AssistantDone(Optional.empty(), Optional.of("end_turn"))
                )
            )))
            .withBean(CompactionSummarizer.class, () -> request -> new CompactSummaryResult(
                "summary",
                new TokenUsage(0, 0, 0, 0)
            ))
            .run(context -> {
                assertThat(context).hasSingleBean(MemoryConsolidationTrigger.class);
                assertThat(context).hasSingleBean(MemoryConsolidationPromptFactory.class);
                assertThat(context).hasSingleBean(MemoryConsolidationRunner.class);
                assertThat(context).hasSingleBean(MemoryConsolidationTurnEndListener.class);
                assertThat(context).hasBean("memoryConsolidationExecutor");
            });
    }

    @Test
    void toolFactoryCreatesCacheStableMemoryConsolidationRuntime() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues("lypi.runtime.cwd=" + tempDir)
            .withBean(AiProviderRuntimePort.class, () -> (snapshot, signal) -> {
                throw new UnsupportedOperationException("not used");
            })
            .withBean(CompactionSummarizer.class, () -> request -> new CompactSummaryResult(
                "summary",
                new TokenUsage(0, 0, 0, 0)
            ))
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(cn.lypi.boot.tool.ToolRuntimeFactoryPort.class)
                    .createMemoryConsolidation(tempDir, context.getBean(EventBus.class));

                assertThat(runtime).isInstanceOf(MemoryConsolidationToolRuntime.class);
                assertThat(runtime.snapshot().tools())
                    .extracting(ToolDescriptor::name)
                    .contains("read", "grep", "glob", "edit", "write")
                    .contains("bash");
            });
    }

    @Test
    void agentCoreFactoryBindsChildToolRuntimeToChildCwd() throws Exception {
        Path childCwd = tempDir.resolve("child-work").toAbsolutePath().normalize();
        java.nio.file.Files.createDirectories(childCwd);
        java.nio.file.Files.writeString(childCwd.resolve("child-file.txt"), "from child cwd");

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyPiToolAutoConfiguration.class,
                LyPiRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                "lypi.runtime.default-provider=openai",
                "lypi.runtime.default-model=gpt-5-mini",
                "lypi.runtime.cwd=" + tempDir
            )
            .withBean(AiProviderRuntimePort.class, () -> new ScriptedAiProvider(List.of(
                List.of(
                    new AssistantStart("msg-tool-call"),
                    new ToolCallDelta("toolu-1", "read", Map.of("path", "child-file.txt"), true),
                    new AssistantDone(Optional.empty(), Optional.of("tool_calls"))
                ),
                List.of(
                    new AssistantStart("msg-final"),
                    new AssistantDone(Optional.empty(), Optional.of("end_turn"))
                )
            )))
            .withBean(CompactionSummarizer.class, () -> request -> new CompactSummaryResult(
                "summary",
                new TokenUsage(0, 0, 0, 0)
            ))
            .run(context -> {
                AgentCoreFactoryPort factory = context.getBean(AgentCoreFactoryPort.class);
                AgentCorePort childCore = factory.create(childCwd, context.getBean(SessionManagerPort.class));

                TurnState state = childCore.execute(new TurnRequest("ses_child", "read child file", Optional.empty(), () -> false));

                List<String> toolTexts = state.newMessages().stream()
                    .flatMap(message -> message.content().stream())
                    .filter(ToolResultContentBlock.class::isInstance)
                    .map(ToolResultContentBlock.class::cast)
                    .map(ToolResultContentBlock::text)
                    .toList();
                assertThat(state.status()).isEqualTo(TurnStatus.COMPLETED);
                assertThat(toolTexts).anySatisfy(text -> assertThat(text).contains("from child cwd"));
                ToolRuntimePort parentToolRuntime = context.getBean(ToolRuntimePort.class);
                assertThat(parentToolRuntime.cwd()).isEqualTo(tempDir.toAbsolutePath().normalize());
            });
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
    void defaultDeliveryGuardKeepsMailboxPendingWhenRuntimeStateHasPendingInteraction() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false, true, false, false))
            .run(context -> assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(mail("ses_parent"))).isFalse());

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false, false, true, false))
            .run(context -> assertThat(context.getBean(MailboxDeliveryGuard.class).canDeliver(mail("ses_parent"))).isFalse());

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false, false, false, true))
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
    void usesResolvedSubagentCommandWhenCommandIsNotConfigured() {
        List<String> inferredCommand = List.of("java", "-jar", "/opt/lypi/lypi-boot.jar", "headless-subagent");

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SubagentCommandResolver.class, () -> new SubagentCommandResolver(null, () ->
                Path.of("/opt/lypi/lypi-boot.jar").toUri()))
            .run(context -> {
                assertThat(ReflectionTestUtils.getField(context.getBean(SubagentProcessRunner.class), "command"))
                    .isEqualTo(inferredCommand);
                assertThat(ReflectionTestUtils.getField(context.getBean(AgentCenterPort.class), "command"))
                    .isEqualTo(inferredCommand);
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
    void defaultChildAgentSnapshotProviderUsesSessionManagerStorageRoot() {
        SessionManagerPort sessionManager = new SessionManagerImpl(tempDir);
        sessionManager.openOrCreate("ses_parent");
        ChildSessionPort childSessions = context -> {
            throw new UnsupportedOperationException("not used");
        };
        new cn.lypi.session.ChildSessionService().create(new ChildSessionRequest(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            tempDir,
            tempDir.resolve("child-exec"),
            1,
            java.util.Optional.of("Scout"),
            java.util.Optional.of("explorer")
        ));

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(SessionManagerPort.class, () -> sessionManager)
            .withBean(ChildSessionPort.class, () -> childSessions)
            .run(context -> assertThat(context.getBean(ChildAgentSnapshotProvider.class).childAgents("ses_parent"))
                .singleElement()
                .satisfies(child -> {
                    assertThat(child.childSessionId()).isEqualTo("ses_child");
                    assertThat(child.parentSpawnEntryId()).isEqualTo("entry_spawn");
                }));
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

    @Test
    void registersAgentSlashCommandHandlerWithRuntimeStateSession() {
        RecordingAgentRegistry registry = new RecordingAgentRegistry();
        RecordingAgentCenter agentCenter = new RecordingAgentCenter();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(AgentRegistryPort.class, () -> registry)
            .withBean(AgentCenterPort.class, () -> agentCenter)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .run(context -> {
                AgentSlashCommandHandler handler = context.getBean(AgentSlashCommandHandler.class);

                handler.handle(Map.of("action", "list", "statuses", "RUNNING"));
                handler.handle(Map.of("action", "interrupt", "agentId", "agent_1"));

                assertThat(handler.command().name()).isEqualTo("agent");
                assertThat(registry.parentSessionId).isEqualTo("ses_parent");
                assertThat(registry.statuses).containsExactly(AgentRunStatus.RUNNING);
                assertThat(agentCenter.interruptedAgentId).isEqualTo("agent_1");
            });
    }

    @Test
    void exposesTuiSlashCommandsFromRegisteredHandlers() {
        NoopMailbox mailbox = new NoopMailbox();
        RecordingAgentRegistry registry = new RecordingAgentRegistry();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(MailboxPort.class, () -> mailbox)
            .withBean(AgentRegistryPort.class, () -> registry)
            .withBean(SessionRuntimeState.class, () -> sessionState("ses_parent", false))
            .run(context -> {
                List<?> slashCommands = (List<?>) context.getBean("tuiSlashCommands");

                assertThat(slashCommands)
                    .extracting(command -> ((SlashCommand) command).name())
                    .containsExactly("mailbox", "agent");
            });
    }

    @Test
    void registersTuiTransportFactoryThatAcceptsSlashCommands() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> assertThat(context).hasSingleBean(JLineTuiTransportFactory.class));
    }

    @Test
    void registersDefaultDiffViewProviderForTuiTransport() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .run(context -> {
                assertThat(context).hasSingleBean(DiffViewProvider.class);
                assertThat(context.getBean(DiffViewProvider.class).currentDiff(tempDir, 1024)).isEmpty();
                assertThat(context).hasBean("jLineTuiTransportLauncher");
            });
    }

    @Test
    void keepsUserProvidedDiffViewProvider() {
        DiffViewProvider provider = (cwd, maxPatchBytes) -> Optional.empty();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(DiffViewProvider.class, () -> provider)
            .run(context -> {
                assertThat(context).hasSingleBean(DiffViewProvider.class);
                assertThat(context.getBean(DiffViewProvider.class)).isSameAs(provider);
            });
    }

    @Test
    void keepsUserProvidedAgentRegistryAndSnapshotProviders() {
        AgentRegistryPort registry = (sessionId, statuses) -> List.of();
        RunningAgentSnapshotProvider runningAgents = parentSessionId -> List.of();
        ChildAgentSnapshotProvider childAgents = parentSessionId -> List.of();

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiRuntimeAutoConfiguration.class)
            .withBean(AgentRegistryPort.class, () -> registry)
            .withBean(RunningAgentSnapshotProvider.class, () -> runningAgents)
            .withBean(ChildAgentSnapshotProvider.class, () -> childAgents)
            .run(context -> {
                assertThat(context).hasSingleBean(AgentRegistryPort.class);
                assertThat(context.getBean(AgentRegistryPort.class)).isSameAs(registry);
                assertThat(context.getBean("runningAgentSnapshotProvider", RunningAgentSnapshotProvider.class)).isSameAs(runningAgents);
                assertThat(context).hasSingleBean(ChildAgentSnapshotProvider.class);
                assertThat(context.getBean(ChildAgentSnapshotProvider.class)).isSameAs(childAgents);
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
        return sessionState(sessionId, hasInterruptibleTool, false, false, false);
    }

    private static SessionRuntimeState sessionState(
        String sessionId,
        boolean hasInterruptibleTool,
        boolean hasActiveTurn,
        boolean hasPendingPermission,
        boolean hasPendingInput
    ) {
        return new SessionRuntimeState(
            sessionId,
            Path.of(".").toAbsolutePath().normalize(),
            "leaf-1",
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO),
            hasInterruptibleTool,
            hasActiveTurn,
            hasPendingPermission,
            hasPendingInput
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

    private static cn.lypi.contracts.session.MessageEntry messageEntry(
        String id,
        String parentId,
        MessageRole role,
        String text
    ) {
        return new cn.lypi.contracts.session.MessageEntry(
            id,
            parentId,
            new cn.lypi.contracts.context.AgentMessage(
                "msg-" + id,
                role,
                MessageKind.TEXT,
                List.of(new cn.lypi.contracts.context.TextContentBlock(text)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            ),
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

    private static final class RecordingAgentRegistry implements AgentRegistryPort {
        private String parentSessionId;
        private java.util.Set<AgentRunStatus> statuses;

        @Override
        public List<AgentView> list(String parentSessionId, java.util.Set<AgentRunStatus> statuses) {
            this.parentSessionId = parentSessionId;
            this.statuses = statuses;
            return List.of();
        }
    }

    private static final class RecordingAgentCenter implements AgentCenterPort {
        private String interruptedAgentId;

        @Override
        public SubagentSpawnResult spawn(SubagentSpawnRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.lypi.contracts.subagent.MailboxCommandResult interrupt(String agentId) {
            this.interruptedAgentId = agentId;
            return cn.lypi.contracts.subagent.MailboxCommandResult.success(null);
        }

        @Override
        public Optional<cn.lypi.contracts.subagent.HeadlessSubagentOutput> readResult(String childSessionId) {
            return Optional.empty();
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
        public SessionHandle openTemporary(String sessionId) {
            return openOrCreate(sessionId);
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

    private static final class ScriptedAiProvider implements AiProviderRuntimePort {
        private final List<List<AssistantStreamEvent>> scripts;
        private int nextScript;

        private ScriptedAiProvider(List<List<AssistantStreamEvent>> scripts) {
            this.scripts = List.copyOf(scripts);
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lypi.contracts.common.AbortSignal signal) {
            if (nextScript >= scripts.size()) {
                throw new AssertionError("没有可用的测试模型流");
            }
            return new ListAssistantEventStream(scripts.get(nextScript++));
        }
    }

    private static final class RecordingAiProvider implements AiProviderRuntimePort {
        private final List<AssistantStreamEvent> events;
        private final AtomicReference<ContextSnapshot> context = new AtomicReference<>();

        private RecordingAiProvider(List<AssistantStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public AssistantEventStream stream(ContextSnapshot context, cn.lypi.contracts.common.AbortSignal signal) {
            this.context.set(context);
            return new ListAssistantEventStream(events);
        }
    }

    private static final class ListAssistantEventStream implements AssistantEventStream {
        private final List<AssistantStreamEvent> events;
        private boolean closed;

        private ListAssistantEventStream(List<AssistantStreamEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public Iterator<AssistantStreamEvent> iterator() {
            return events.iterator();
        }

        @Override
        public AssistantStreamResult result() {
            return new AssistantStreamResult("", events, Optional.empty(), Optional.empty(), !closed, false, Optional.empty());
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static AgentMessage userMessage(String id, String text) {
        return textMessage(id, MessageRole.USER, text);
    }

    private static AgentMessage assistantMessage(String id, String text) {
        return textMessage(id, MessageRole.ASSISTANT, text);
    }

    private static AgentMessage textMessage(String id, MessageRole role, String text) {
        return new AgentMessage(
            id,
            role,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
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

    private static final class McpSnapshotTool implements Tool<Map<String, Object>, String> {
        @Override
        public String name() {
            return "mcp__filesystem__read_file";
        }

        @Override
        public List<String> aliases() {
            return List.of();
        }

        @Override
        public cn.lypi.contracts.common.JsonSchema inputSchema() {
            return new cn.lypi.contracts.common.JsonSchema(Map.of("type", "object"));
        }

        @Override
        public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
            return new ValidationResult(true, List.of());
        }

        @Override
        public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
            return new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "只读 MCP 工具",
                java.util.Optional.empty(),
                Map.of()
            );
        }

        @Override
        public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
            return new ToolResult<>("{}", false, List.of(serializeForContext("{}")), java.util.Optional.empty());
        }

        @Override
        public boolean isReadOnly(Map<String, Object> input) {
            return true;
        }

        @Override
        public cn.lypi.contracts.tool.InterruptBehavior interruptBehavior() {
            return cn.lypi.contracts.tool.InterruptBehavior.CANCEL;
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
            return "mcp read_file " + input;
        }

        @Override
        public AgentMessage serializeForContext(String output) {
            return new AgentMessage(
                "msg_mcp_tool_result",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("toolu_mcp", output, false)),
                Instant.EPOCH,
                java.util.Optional.empty(),
                java.util.Optional.empty()
            );
        }
    }
}
