package cn.lycode.boot.runtime;

import cn.lycode.agent.AgentCoreRuntimePorts;
import cn.lycode.agent.ContextAssembler;
import cn.lycode.agent.ContextBudgetEstimator;
import cn.lycode.agent.DefaultCompactionRuntime;
import cn.lycode.agent.DefaultContextAssembler;
import cn.lycode.agent.DefaultTurnExecutor;
import cn.lycode.agent.NoopMemoryExtractionWorker;
import cn.lycode.agent.TurnIds;
import cn.lycode.agent.branch.AiBranchSummarizer;
import cn.lycode.agent.branch.BranchSummaryContextBuilder;
import cn.lycode.agent.branch.BranchSummaryInstructionFactory;
import cn.lycode.agent.compact.CompactionCoordinator;
import cn.lycode.agent.compact.CompactionSummarizer;
import cn.lycode.agent.compact.DefaultCompactionCoordinator;
import cn.lycode.agent.compact.DefaultCompactionPlanner;
import cn.lycode.boot.BootstrapService;
import cn.lycode.boot.tool.LyCodePermissionsProperties;
import cn.lycode.boot.tool.ToolRuntimeFactoryPort;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.model.ModelCatalogPort;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.runtime.AgentCenterPort;
import cn.lycode.contracts.runtime.AgentCommunicationPort;
import cn.lycode.contracts.runtime.AgentCoreFactoryPort;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.runtime.AgentRegistryPort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.runtime.AppEntry;
import cn.lycode.contracts.runtime.ChildSessionPort;
import cn.lycode.contracts.runtime.CompactStateBackfillPort;
import cn.lycode.contracts.runtime.CompactionRuntimePort;
import cn.lycode.contracts.runtime.LyCodeRuntime;
import cn.lycode.contracts.runtime.ProviderLoginPort;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerFactoryPort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.SessionStorageRootPort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.security.PermissionProfileSelection;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.security.PermissionRule;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.contracts.transport.TransportAdapter;
import cn.lycode.contracts.tui.DiffViewProvider;
import cn.lycode.contracts.tui.NewSessionController;
import cn.lycode.contracts.tui.ResumeSessionController;
import cn.lycode.contracts.tui.SessionRuntimeState;
import cn.lycode.contracts.tui.SlashCommand;
import cn.lycode.resource.DefaultResourceRuntime;
import cn.lycode.runtime.event.InMemoryEventBus;
import cn.lycode.runtime.memory.JsonlMemoryConsolidationAuditSink;
import cn.lycode.runtime.memory.MemoryConsolidationAuditSink;
import cn.lycode.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lycode.runtime.memory.MemoryConsolidationRunner;
import cn.lycode.runtime.memory.MemoryConsolidationTrigger;
import cn.lycode.runtime.memory.MemoryConsolidationTurnEndListener;
import cn.lycode.runtime.memory.QuietEventBus;
import cn.lycode.runtime.subagent.AgentCompactStateBackfill;
import cn.lycode.runtime.subagent.ChildAgentSnapshot;
import cn.lycode.runtime.subagent.ChildAgentSnapshotProvider;
import cn.lycode.runtime.subagent.DefaultAgentCenter;
import cn.lycode.runtime.subagent.DefaultAgentRegistry;
import cn.lycode.runtime.subagent.DefaultMailboxService;
import cn.lycode.runtime.subagent.JsonSubagentProcessRunner;
import cn.lycode.runtime.subagent.JsonlMailboxStore;
import cn.lycode.runtime.subagent.RunningAgentSnapshotProvider;
import cn.lycode.runtime.subagent.SubagentProcessRunner;
import cn.lycode.security.ExecPolicyRuleFileReader;
import cn.lycode.security.PermissionProfileConfigCompiler;
import cn.lycode.session.ChildSessionService;
import cn.lycode.session.ChildSessionView;
import cn.lycode.session.DefaultSessionManagerFactory;
import cn.lycode.session.GitWorkingTreeDiffQuery;
import cn.lycode.session.SessionManagerImpl;
import cn.lycode.session.SessionTreeQuery;
import cn.lycode.transport.tui.AgentSlashCommandHandler;
import cn.lycode.transport.tui.JLineTuiTransport;
import cn.lycode.transport.tui.JLineTuiTransportFactory;
import cn.lycode.tool.FilePermissionAmendmentStore;
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

    static SecurityRuntimePort securityRuntime(LyCodeRuntimeProperties properties) {
        Path rulesFile = properties.getCwd().resolve("rules").resolve("default.rules");
        List<PermissionRule> legacyRules = new ExecPolicyRuleFileReader().read(rulesFile);
        return new AmendmentAwareSecurityRuntime(legacyRules, new FilePermissionAmendmentStore(properties.getCwd()));
    }

    static SessionManagerPort sessionManager(LyCodeRuntimeProperties properties) {
        return sessionManager(properties, PermissionRuntimeState.fromLegacy(properties.getPermissionMode()));
    }

    static SessionManagerPort sessionManager(
        LyCodeRuntimeProperties properties,
        LyCodePermissionsProperties permissionsProperties,
        PermissionProfileSelection selection
    ) {
        PermissionRuntimeState modeState = PermissionRuntimeState.forMode(properties.getPermissionMode());
        boolean configuredProfileOverridesMode = permissionsProperties.hasExplicitProfileConfig()
            || !":workspace".equals(selection.activePermissionProfile().id());
        PermissionRuntimeState runtimeState = new PermissionRuntimeState(
            permissionsProperties.hasExplicitApprovalPolicyConfig()
                ? permissionsProperties.getApprovalPolicy().toApprovalPolicy()
                : modeState.approvalPolicy(),
            configuredProfileOverridesMode ? selection.activePermissionProfile() : modeState.activePermissionProfile(),
            configuredProfileOverridesMode ? selection.permissionProfile() : modeState.permissionProfile(),
            modeState.legacyBehavior(),
            properties.getPermissionMode()
        );
        return sessionManager(properties, runtimeState);
    }

    private static SessionManagerPort sessionManager(
        LyCodeRuntimeProperties properties,
        PermissionRuntimeState permissionRuntimeState
    ) {
        return new SessionManagerImpl(
            properties.getCwd(),
            new ModelSelection(properties.getDefaultProvider(), properties.getDefaultModel(), properties.getThinkingLevel()),
            properties.getThinkingLevel(),
            properties.getAgentMode(),
            permissionRuntimeState
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
        return compactionCoordinator(sessionManager, contextAssembler, eventBus, summarizer, CompactStateBackfillPort.none(), clock);
    }

    static CompactionCoordinator compactionCoordinator(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionSummarizer summarizer,
        CompactStateBackfillPort compactStateBackfill,
        Clock clock
    ) {
        return new DefaultCompactionCoordinator(
            sessionManager,
            contextAssembler,
            eventBus,
            new DefaultCompactionPlanner(),
            summarizer,
            compactStateBackfill,
            clock
        );
    }

    static CompactionRuntimePort compactionRuntime(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        ToolRuntimePort toolRuntime,
        CompactionSummarizer summarizer,
        Clock clock
    ) {
        return compactionRuntime(
            sessionManager,
            contextAssembler,
            eventBus,
            toolRuntime,
            summarizer,
            CompactStateBackfillPort.none(),
            clock
        );
    }

    static CompactionRuntimePort compactionRuntime(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        ToolRuntimePort toolRuntime,
        CompactionSummarizer summarizer,
        CompactStateBackfillPort compactStateBackfill,
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
                compactStateBackfill,
                clock
            ),
            toolRuntime
        );
    }

    static AgentCorePort agentCore(
        LyCodeRuntimeProperties properties,
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
        return agentCore(
            properties,
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            compactionCoordinator,
            CompactStateBackfillPort.none(),
            clock
        );
    }

    static AgentCorePort agentCore(
        LyCodeRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        CompactStateBackfillPort compactStateBackfill,
        Clock clock
    ) {
        return agentCore(
            properties,
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            compactionCoordinator,
            compactStateBackfill,
            AgentCommunicationPort.none(),
            clock
        );
    }

    static AgentCorePort agentCore(
        LyCodeRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator,
        CompactStateBackfillPort compactStateBackfill,
        AgentCommunicationPort agentCommunication,
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
            compactStateBackfill,
            agentCommunication,
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
        ObjectProvider<CompactStateBackfillPort> compactStateBackfill,
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
                CompactStateBackfillPort resolvedCompactStateBackfill = compactStateBackfill.getIfAvailable(CompactStateBackfillPort::none);
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
                    resolvedCompactStateBackfill,
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
                        resolvedCompactStateBackfill,
                        AgentCommunicationPort.none(),
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

    static MemoryConsolidationAuditSink memoryConsolidationAuditSink(LyCodeRuntimeProperties properties) {
        return new JsonlMemoryConsolidationAuditSink(properties.getCwd());
    }

    static ExecutorService memoryConsolidationExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "lycode-memory-consolidation");
            thread.setDaemon(true);
            return thread;
        });
    }

    static MemoryConsolidationRunner memoryConsolidationRunner(
        LyCodeRuntimeProperties properties,
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

    static SessionRuntimeState sessionRuntimeState(LyCodeRuntimeProperties properties, SessionManagerPort sessionManager) {
        var handle = properties.isSessionIdConfigured()
            ? sessionManager.openOrCreate(properties.getSessionId())
            : sessionManager.openTemporary(properties.getSessionId());
        var sessionContext = sessionManager.context(handle.leafId());
        return new SessionRuntimeState(
            handle.sessionId(),
            properties.getCwd(),
            handle.leafId(),
            new ModelSelection(properties.getDefaultProvider(), properties.getDefaultModel(), properties.getThinkingLevel()),
            properties.getThinkingLevel(),
            properties.getAgentMode(),
            sessionContext.permissionRuntimeState(),
            new ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, BigDecimal.ZERO),
            List.of(),
            false,
            false,
            false,
            false
        );
    }

    static BootstrapService bootstrapService(
        LyCodeRuntimeProperties properties,
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
        LyCodeRuntimeProperties properties,
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
        CompactionRuntimePort compactionRuntime,
        ModelCatalogPort modelCatalog,
        ProviderLoginPort providerLogin
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
                compactionRuntime,
                modelCatalog,
                providerLogin
            );
    }

    static ResumeSessionController resumeSessionController(
        LyCodeRuntimeProperties properties,
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
        LyCodeRuntimeProperties properties,
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

    static LyCodeRuntime lyCodeRuntime(
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
        return new LyCodeRuntime(
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

    static ApplicationRunner applicationRunner(AppEntry appEntry, LyCodeRuntimeProperties properties) {
        return args -> {
            if (isHeadlessSubagent(args)) {
                return;
            }
            appEntry.start(new cn.lycode.contracts.bootstrap.BootstrapRequest(
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

    static DefaultMailboxService mailboxPort(JsonlMailboxStore store, Clock clock) {
        return new DefaultMailboxService(store, clock);
    }

    static AgentRegistryPort agentRegistry(
        SessionManagerPort parentSession,
        DefaultMailboxService mailbox,
        RunningAgentSnapshotProvider runningAgents,
        ChildAgentSnapshotProvider childAgents
    ) {
        return new DefaultAgentRegistry(parentSession, mailbox, runningAgents, childAgents);
    }

    static CompactStateBackfillPort compactStateBackfill(AgentRegistryPort registry) {
        return registry == null ? CompactStateBackfillPort.none() : new AgentCompactStateBackfill(registry);
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

    static SubagentCommandResolver subagentCommandResolver(LyCodeSubagentProperties properties) {
        return new SubagentCommandResolver(properties);
    }

    static SubagentProcessRunner subagentProcessRunner(SubagentCommandResolver subagentCommandResolver) {
        return new JsonSubagentProcessRunner(subagentCommandResolver.resolve());
    }

    static AgentCenterPort agentCenter(
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        ModelCatalogPort modelCatalog,
        SubagentCommandResolver subagentCommandResolver,
        Clock clock
    ) {
        List<String> command = subagentCommandResolver.resolve();
        return new DefaultAgentCenter(
            command,
            childSessions,
            parentSession,
            sessionStorageRoot(parentSession),
            processRunner,
            mailbox,
            modelCatalog,
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
        if (args.containsOption("lycode.headless.subagent") || args.containsOption("lycode-headless-subagent")) {
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

}
