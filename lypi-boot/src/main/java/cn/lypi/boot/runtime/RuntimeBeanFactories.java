package cn.lypi.boot.runtime;

import cn.lypi.agent.AgentCoreRuntimePorts;
import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextBudgetEstimator;
import cn.lypi.agent.DefaultCompactionRuntime;
import cn.lypi.agent.DefaultContextAssembler;
import cn.lypi.agent.DefaultTurnExecutor;
import cn.lypi.agent.NoopMemoryExtractionWorker;
import cn.lypi.agent.TurnIds;
import cn.lypi.agent.branch.AiBranchSummarizer;
import cn.lypi.agent.branch.BranchSummaryContextBuilder;
import cn.lypi.agent.branch.BranchSummaryInstructionFactory;
import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.boot.BootstrapService;
import cn.lypi.boot.tool.ToolRuntimeFactoryPort;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.model.ModelCatalogPort;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.CompactionRuntimePort;
import cn.lypi.contracts.runtime.LyPiRuntime;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.SessionStorageRootPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.resource.DefaultResourceRuntime;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.memory.JsonlMemoryConsolidationAuditSink;
import cn.lypi.runtime.memory.MemoryConsolidationAuditSink;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRunner;
import cn.lypi.runtime.memory.MemoryConsolidationTrigger;
import cn.lypi.runtime.memory.MemoryConsolidationTurnEndListener;
import cn.lypi.runtime.memory.QuietEventBus;
import cn.lypi.runtime.subagent.ChildAgentSnapshot;
import cn.lypi.runtime.subagent.ChildAgentSnapshotProvider;
import cn.lypi.runtime.subagent.DefaultAgentCenter;
import cn.lypi.runtime.subagent.DefaultAgentRegistry;
import cn.lypi.runtime.subagent.DefaultMailboxService;
import cn.lypi.runtime.subagent.JsonSubagentProcessRunner;
import cn.lypi.runtime.subagent.JsonlMailboxStore;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.MailboxDeliveryService;
import cn.lypi.runtime.subagent.RunningAgentSnapshotProvider;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.security.DefaultPolicyEngine;
import cn.lypi.security.ExecPolicyRuleFileReader;
import cn.lypi.session.ChildSessionService;
import cn.lypi.session.ChildSessionView;
import cn.lypi.session.DefaultSessionManagerFactory;
import cn.lypi.session.GitWorkingTreeDiffQuery;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.session.SessionTreeQuery;
import cn.lypi.transport.tui.AgentSlashCommandHandler;
import cn.lypi.transport.tui.JLineTuiTransport;
import cn.lypi.transport.tui.JLineTuiTransportFactory;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

final class RuntimeBeanFactories {
    private static final Path DEFAULT_CWD = Path.of(".").toAbsolutePath().normalize();

    private RuntimeBeanFactories() {
    }

    static EventBus eventBus() {
        return new InMemoryEventBus();
    }

    static SecurityRuntimePort securityRuntime(LyPiRuntimeProperties properties) {
        Path rulesFile = properties.getCwd().resolve("rules").resolve("default.rules");
        return new DefaultPolicyEngine(new ExecPolicyRuleFileReader().read(rulesFile));
    }

    static SessionManagerPort sessionManager(LyPiRuntimeProperties properties) {
        return new SessionManagerImpl(
            properties.getCwd(),
            new ModelSelection(properties.getDefaultProvider(), properties.getDefaultModel(), properties.getThinkingLevel()),
            properties.getThinkingLevel(),
            properties.getAgentMode(),
            properties.getPermissionMode()
        );
    }

    static SessionManagerFactoryPort sessionManagerFactory() {
        return new DefaultSessionManagerFactory();
    }

    static ResourceRuntimePort resourceRuntime() {
        return new DefaultResourceRuntime();
    }

    static ContextAssembler contextAssembler(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ModelCatalogPort modelCatalog
    ) {
        return new DefaultContextAssembler(
            sessionManager,
            resourceRuntime,
            new ContextBudgetEstimator(modelCatalog)
        );
    }

    static CompactionSummarizer unavailableCompactionSummarizer() {
        return request -> {
            throw new IllegalStateException("AI compaction summarizer is unavailable");
        };
    }

    static CompactionCoordinator compactionCoordinator(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionSummarizer summarizer,
        Clock clock
    ) {
        return new DefaultCompactionCoordinator(
            sessionManager,
            contextAssembler,
            eventBus,
            new DefaultCompactionPlanner(),
            summarizer,
            clock
        );
    }

    static CompactionRuntimePort compactionRuntime(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionSummarizer summarizer,
        Clock clock
    ) {
        return new DefaultCompactionRuntime(
            contextAssembler,
            new DefaultCompactionCoordinator(
                sessionManager,
                contextAssembler,
                eventBus,
                DefaultCompactionRuntime.manualPlanner(),
                summarizer,
                clock
            )
        );
    }

    static AgentCorePort agentCore(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        Clock clock
    ) {
        AgentCoreRuntimePorts ports = new AgentCoreRuntimePorts(
            properties.getCwd(),
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            null,
            compactionCoordinator,
            new NoopMemoryExtractionWorker()
        );
        return new DefaultTurnExecutor(ports, TurnIds.random(), clock);
    }

    static AgentCoreFactoryPort agentCoreFactory(
        ObjectProvider<AiProviderRuntimePort> aiProvider,
        ObjectProvider<ToolRuntimePort> toolRuntime,
        ObjectProvider<ToolRuntimeFactoryPort> toolRuntimeFactory,
        ObjectProvider<SecurityRuntimePort> securityRuntime,
        ObjectProvider<ResourceRuntimePort> resourceRuntime,
        EventBus eventBus,
        ObjectProvider<CompactionSummarizer> compactionSummarizer,
        ObjectProvider<ModelCatalogPort> modelCatalog,
        Clock clock
    ) {
        return new AgentCoreFactoryPort() {
            @Override
            public AgentCorePort create(Path cwd, SessionManagerPort sessionManager) {
                return create(cwd, sessionManager, null);
            }

            @Override
            public AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy toolPolicy) {
                ToolRuntimeFactoryPort resolvedToolRuntimeFactory = toolRuntimeFactory.getIfAvailable();
                ToolRuntimePort resolvedToolRuntime = resolvedToolRuntimeFactory == null
                    ? toolRuntime.getObject()
                    : resolvedToolRuntimeFactory.create(cwd, toolPolicy);
                return createWithPorts(cwd, sessionManager, resolvedToolRuntime, eventBus);
            }

            @Override
            public AgentCorePort create(
                Path cwd,
                SessionManagerPort sessionManager,
                ToolRuntimePort toolRuntime,
                EventBus eventBus
            ) {
                return createWithPorts(cwd, sessionManager, toolRuntime, eventBus);
            }

            private AgentCorePort createWithPorts(
                Path cwd,
                SessionManagerPort sessionManager,
                ToolRuntimePort resolvedToolRuntime,
                EventBus resolvedEventBus
            ) {
                AiProviderRuntimePort resolvedAiProvider = aiProvider.getObject();
                SecurityRuntimePort resolvedSecurityRuntime = securityRuntime.getObject();
                ResourceRuntimePort resolvedResourceRuntime = resourceRuntime.getObject();
                CompactionSummarizer resolvedCompactionSummarizer = compactionSummarizer.getObject();
                DefaultContextAssembler assembler = new DefaultContextAssembler(
                    sessionManager,
                    resolvedResourceRuntime,
                    new ContextBudgetEstimator(modelCatalog.getIfAvailable())
                );
                DefaultCompactionCoordinator compactionCoordinator = new DefaultCompactionCoordinator(
                    sessionManager,
                    assembler,
                    resolvedEventBus,
                    new DefaultCompactionPlanner(),
                    resolvedCompactionSummarizer,
                    clock
                );
                return new DefaultTurnExecutor(
                    new AgentCoreRuntimePorts(
                        cwd,
                        sessionManager,
                        resolvedAiProvider,
                        resolvedToolRuntime,
                        resolvedSecurityRuntime,
                        resolvedResourceRuntime,
                        resolvedEventBus,
                        assembler,
                        null,
                        compactionCoordinator,
                        new NoopMemoryExtractionWorker()
                    ),
                    TurnIds.random(),
                    clock
                );
            }
        };
    }

    static MemoryConsolidationTrigger memoryConsolidationTrigger() {
        return new MemoryConsolidationTrigger();
    }

    static MemoryConsolidationPromptFactory memoryConsolidationPromptFactory() {
        return new MemoryConsolidationPromptFactory();
    }

    static MemoryConsolidationAuditSink memoryConsolidationAuditSink(LyPiRuntimeProperties properties) {
        return new JsonlMemoryConsolidationAuditSink(properties.getCwd());
    }

    static ExecutorService memoryConsolidationExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lypi-memory-consolidation");
            thread.setDaemon(true);
            return thread;
        });
    }

    static MemoryConsolidationRunner memoryConsolidationRunner(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        ToolRuntimeFactoryPort toolRuntimeFactory,
        MemoryConsolidationPromptFactory promptFactory,
        MemoryConsolidationAuditSink auditSink
    ) {
        return request -> {
            QuietEventBus quietEventBus = new QuietEventBus();
            ToolRuntimePort restrictedToolRuntime = toolRuntimeFactory.createMemoryConsolidation(properties.getCwd(), quietEventBus);
            new BootMemoryConsolidationRunner(
                properties.getCwd(),
                sessionManager,
                new AgentCoreFactoryPort() {
                    @Override
                    public AgentCorePort create(Path cwd, SessionManagerPort forkSessionManager) {
                        return agentCoreFactory.create(cwd, forkSessionManager, restrictedToolRuntime, quietEventBus);
                    }
                },
                promptFactory,
                auditSink
            ).run(request);
        };
    }

    static MemoryConsolidationTurnEndListener memoryConsolidationTurnEndListener(
        EventBus eventBus,
        SessionManagerPort sessionManager,
        MemoryConsolidationTrigger trigger,
        MemoryConsolidationRunner runner,
        java.util.concurrent.Executor executor,
        MemoryConsolidationAuditSink auditSink
    ) {
        return new MemoryConsolidationTurnEndListener(eventBus, sessionManager, trigger, runner, executor, auditSink);
    }

    static SessionRuntimeState sessionRuntimeState(LyPiRuntimeProperties properties, SessionManagerPort sessionManager) {
        var handle = properties.isSessionIdConfigured()
            ? sessionManager.openOrCreate(properties.getSessionId())
            : sessionManager.openTemporary(properties.getSessionId());
        return new SessionRuntimeState(
            handle.sessionId(),
            properties.getCwd(),
            handle.leafId(),
            new ModelSelection(properties.getDefaultProvider(), properties.getDefaultModel(), properties.getThinkingLevel()),
            properties.getThinkingLevel(),
            properties.getAgentMode(),
            properties.getPermissionMode(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    static BootstrapService bootstrapService(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ToolRuntimePort toolRuntime
    ) {
        return new DefaultBootstrapService(properties, sessionManager, resourceRuntime, toolRuntime);
    }

    static AppEntry appEntry(
        BootstrapService bootstrapService,
        AgentCorePort agentCore,
        EventBus eventBus,
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        List<TransportLauncher> transportLaunchers
    ) {
        return new DefaultAppEntry(
            bootstrapService,
            agentCore,
            eventBus,
            properties,
            sessionManager,
            List.copyOf(transportLaunchers)
        );
    }

    static JLineTuiTransportFactory jLineTuiTransportFactory(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) {
        return (state, core, events, terminal, diffViewProvider, resumeController, newSessionController, slashCommands) ->
            JLineTuiTransport.open(
                state,
                core,
                events,
                terminal,
                diffViewProvider,
                slashCommands,
                resumeController,
                newSessionController,
                sessionManager,
                resourceRuntime,
                compactionRuntime
            );
    }

    static ResumeSessionController resumeSessionController(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        EventBus eventBus,
        AiProviderRuntimePort provider
    ) {
        return new DefaultResumeSessionController(
            properties.getCwd(),
            sessionManager,
            eventBus,
            provider == null
                ? null
                : new AiBranchSummarizer(
                    provider,
                    new BranchSummaryContextBuilder(new BranchSummaryInstructionFactory())
                )
        );
    }

    static NewSessionController newSessionController(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        EventBus eventBus
    ) {
        return new DefaultNewSessionController(properties.getCwd(), sessionManager, eventBus);
    }

    static DiffViewProvider diffViewProvider() {
        return (cwd, maxPatchBytes) -> cwd == null
            ? Optional.empty()
            : new GitWorkingTreeDiffQuery(cwd).diffView(maxPatchBytes);
    }

    static TransportLauncher jLineTuiTransportLauncher(
        JLineTuiTransportFactory factory,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        List<SlashCommand> slashCommands
    ) {
        return new JLineTuiTransportLauncher(
            factory,
            diffViewProvider,
            resumeController,
            newSessionController,
            List.copyOf(slashCommands)
        );
    }

    static LyPiRuntime lyPiRuntime(
        AppEntry appEntry,
        SessionManagerPort sessionManager,
        AgentCorePort agentCore,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        List<TransportAdapter> transports
    ) {
        return new LyPiRuntime(
            appEntry,
            sessionManager,
            agentCore,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            compactionRuntime,
            List.copyOf(transports)
        );
    }

    static ApplicationRunner applicationRunner(AppEntry appEntry, LyPiRuntimeProperties properties) {
        return args -> {
            if (isHeadlessSubagent(args)) {
                return;
            }
            appEntry.start(new cn.lypi.contracts.bootstrap.BootstrapRequest(
                properties.getCwd(),
                args == null ? List.of() : args.getNonOptionArgs(),
                properties.isSessionIdConfigured() ? Optional.of(properties.getSessionId()) : Optional.empty(),
                Optional.ofNullable(properties.getInitialPrompt())
            ));
        };
    }

    static TransportEventConnector transportEventConnector(
        EventBus eventBus,
        List<TransportAdapter> transports,
        SessionRuntimeState state
    ) {
        TransportEventConnector connector = new TransportEventConnector(eventBus, List.copyOf(transports));
        if (state != null) {
            connector.attachAll(state);
        }
        return connector;
    }

    static Clock clock() {
        return Clock.systemUTC();
    }

    static ChildSessionPort childSessionPort() {
        return new ChildSessionService();
    }

    static ChildAgentSnapshotProvider childAgentSnapshotProvider(SessionManagerPort sessionManager) {
        SessionTreeQuery query = new SessionTreeQuery(sessionStorageRoot(sessionManager));
        return parentSessionId -> query.children(parentSessionId).stream()
            .map(RuntimeBeanFactories::childAgentSnapshot)
            .toList();
    }

    static JsonlMailboxStore jsonlMailboxStore(SessionManagerPort sessionManager) {
        return new JsonlMailboxStore(sessionStorageRoot(sessionManager));
    }

    static DefaultMailboxService mailboxPort(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        return new DefaultMailboxService(store, sessionManager, clock);
    }

    static MailboxDeliveryGuard mailboxDeliveryGuard(
        Supplier<SessionRuntimeState> runtimeStateSupplier,
        SessionManagerPort sessionManager
    ) {
        return message -> {
            if (message == null) {
                return false;
            }
            SessionRuntimeState runtimeState = runtimeStateSupplier.get();
            if (runtimeState == null
                || runtimeState.hasInterruptibleTool()
                || runtimeState.hasActiveTurn()
                || runtimeState.hasPendingPermission()
                || runtimeState.hasPendingInput()) {
                return false;
            }
            return message.parentSessionId().equals(runtimeState.sessionId())
                && currentBranchContainsSpawnEntry(sessionManager, runtimeState, message.parentSpawnEntryId());
        };
    }

    static MailboxDeliveryService mailboxDeliveryService(DefaultMailboxService mailbox, MailboxDeliveryGuard guard) {
        return new MailboxDeliveryService(mailbox, guard);
    }

    static MailboxSlashCommandHandler mailboxSlashCommandHandler(
        MailboxPort mailbox,
        Supplier<SessionRuntimeState> runtimeStateSupplier,
        SessionManagerPort sessionManager
    ) {
        return new MailboxSlashCommandHandler(mailbox, () -> {
            SessionRuntimeState runtimeState = runtimeStateSupplier.get();
            if (runtimeState != null) {
                return runtimeState.sessionId();
            }
            return sessionManager.currentView().sessionId();
        });
    }

    static AgentRegistryPort agentRegistry(
        SessionManagerPort parentSession,
        DefaultMailboxService mailbox,
        RunningAgentSnapshotProvider runningAgents,
        ChildAgentSnapshotProvider childAgents
    ) {
        return new DefaultAgentRegistry(parentSession, mailbox, runningAgents, childAgents);
    }

    static AgentSlashCommandHandler agentSlashCommandHandler(
        AgentRegistryPort registry,
        AgentCenterPort agentCenter,
        Supplier<SessionRuntimeState> runtimeStateSupplier,
        SessionManagerPort sessionManager
    ) {
        return new AgentSlashCommandHandler(registry, agentCenter, () -> {
            SessionRuntimeState runtimeState = runtimeStateSupplier.get();
            if (runtimeState != null) {
                return runtimeState.sessionId();
            }
            return sessionManager.currentView().sessionId();
        });
    }

    static List<SlashCommand> tuiSlashCommands(List<SlashCommand> commands) {
        return List.copyOf(commands);
    }

    static RunningAgentSnapshotProvider runningAgentSnapshotProvider(
        List<RunningAgentSnapshotProvider> runningAgents,
        AgentCenterPort agentCenter
    ) {
        List<RunningAgentSnapshotProvider> explicitProviders = runningAgents.stream()
            .filter(provider -> !(provider instanceof AgentCenterPort))
            .toList();
        if (!explicitProviders.isEmpty()) {
            return explicitProviders.getFirst();
        }
        if (agentCenter instanceof RunningAgentSnapshotProvider provider) {
            return provider;
        }
        return ignored -> List.of();
    }

    static SubagentCommandResolver subagentCommandResolver(LyPiSubagentProperties properties) {
        return new SubagentCommandResolver(properties);
    }

    static SubagentProcessRunner subagentProcessRunner(SubagentCommandResolver subagentCommandResolver) {
        return new JsonSubagentProcessRunner(subagentCommandResolver.resolve());
    }

    static AgentCenterPort agentCenter(
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        SessionManagerFactoryPort sessionManagerFactory,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        MailboxDeliveryService deliveryService,
        SubagentCommandResolver subagentCommandResolver,
        Clock clock
    ) {
        List<String> command = subagentCommandResolver.resolve();
        return new DefaultAgentCenter(
            command,
            childSessions,
            parentSession,
            sessionStorageRoot(parentSession),
            sessionManagerFactory,
            processRunner,
            mailbox,
            deliveryService,
            clock
        );
    }

    static Path sessionStorageRoot(SessionManagerPort sessionManager) {
        if (sessionManager instanceof SessionStorageRootPort storageRoot) {
            return storageRoot.sessionStorageRoot();
        }
        return DEFAULT_CWD;
    }

    private static boolean isHeadlessSubagent(ApplicationArguments args) {
        if (args == null) {
            return false;
        }
        if (args.containsOption("lypi.headless.subagent") || args.containsOption("lypi-headless-subagent")) {
            return true;
        }
        return List.of(args.getSourceArgs()).contains("headless-subagent");
    }

    private static ChildAgentSnapshot childAgentSnapshot(ChildSessionView child) {
        return new ChildAgentSnapshot(
            child.sessionId(),
            child.parentSessionId().orElse(""),
            child.parentSpawnEntryId().orElse(""),
            child.agentName(),
            child.agentRole()
        );
    }

    private static boolean currentBranchContainsSpawnEntry(
        SessionManagerPort sessionManager,
        SessionRuntimeState runtimeState,
        String parentSpawnEntryId
    ) {
        if (parentSpawnEntryId == null || parentSpawnEntryId.isBlank()) {
            return false;
        }
        try {
            return sessionManager.branch(runtimeState.currentBranchLeafId()).stream()
                .map(SessionEntry::id)
                .anyMatch(parentSpawnEntryId::equals);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
