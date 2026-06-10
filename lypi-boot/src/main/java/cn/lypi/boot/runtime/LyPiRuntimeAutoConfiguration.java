package cn.lypi.boot.runtime;

import cn.lypi.agent.AgentCoreRuntimePorts;
import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.ContextBudgetEstimator;
import cn.lypi.agent.DefaultContextAssembler;
import cn.lypi.agent.DefaultCompactionRuntime;
import cn.lypi.agent.DefaultTurnExecutor;
import cn.lypi.agent.NoopMemoryExtractionWorker;
import cn.lypi.agent.TurnIds;
import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
import cn.lypi.boot.BootstrapService;
import cn.lypi.boot.tool.ToolRuntimeFactoryPort;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
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
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.resource.DefaultResourceRuntime;
import cn.lypi.runtime.event.InMemoryEventBus;
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
import cn.lypi.session.ChildSessionService;
import cn.lypi.session.ChildSessionView;
import cn.lypi.session.DefaultSessionManagerFactory;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(after = {
    cn.lypi.boot.ai.LyPiAiAutoConfiguration.class,
    cn.lypi.boot.tool.LyPiToolAutoConfiguration.class
})
@EnableConfigurationProperties({LyPiRuntimeProperties.class, LyPiSubagentProperties.class})
public class LyPiRuntimeAutoConfiguration {
    private static final Path DEFAULT_CWD = Path.of(".").toAbsolutePath().normalize();

    /**
     * 创建默认事件总线。
     */
    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus eventBus() {
        return new InMemoryEventBus();
    }

    /**
     * 创建默认权限策略运行时。
     */
    @Bean
    @ConditionalOnMissingBean(SecurityRuntimePort.class)
    public SecurityRuntimePort securityRuntime() {
        return new DefaultPolicyEngine();
    }

    /**
     * 创建默认 session 管理器。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerPort.class)
    public SessionManagerPort sessionManager(LyPiRuntimeProperties properties) {
        return new SessionManagerImpl(
            properties.getCwd(),
            new ModelSelection(properties.getDefaultProvider(), properties.getDefaultModel(), properties.getThinkingLevel()),
            properties.getThinkingLevel(),
            properties.getAgentMode(),
            properties.getPermissionMode()
        );
    }

    /**
     * 创建默认 session manager factory。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerFactoryPort.class)
    public SessionManagerFactoryPort sessionManagerFactory() {
        return new DefaultSessionManagerFactory();
    }

    /**
     * 创建默认资源运行时。
     */
    @Bean
    @ConditionalOnMissingBean(ResourceRuntimePort.class)
    public ResourceRuntimePort resourceRuntime() {
        return new DefaultResourceRuntime();
    }

    /**
     * 创建默认 context assembler。
     */
    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime
    ) {
        return new DefaultContextAssembler(sessionManager, resourceRuntime, new ContextBudgetEstimator());
    }

    /**
     * 创建默认 compaction summarizer。
     */
    @Bean
    @ConditionalOnMissingBean(CompactionSummarizer.class)
    public CompactionSummarizer compactionSummarizer() {
        return request -> {
            throw new IllegalStateException("AI compaction summary is disabled");
        };
    }

    /**
     * 创建默认 compaction coordinator。
     */
    @Bean
    @ConditionalOnMissingBean(CompactionCoordinator.class)
    public CompactionCoordinator compactionCoordinator(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionSummarizer summarizer
    ) {
        return new DefaultCompactionCoordinator(
            sessionManager,
            contextAssembler,
            eventBus,
            new DefaultCompactionPlanner(),
            summarizer,
            Clock.systemUTC()
        );
    }

    /**
     * 创建默认手动压缩运行时。
     */
    @Bean
    @ConditionalOnMissingBean(CompactionRuntimePort.class)
    public CompactionRuntimePort compactionRuntime(
        SessionManagerPort sessionManager,
        ContextAssembler contextAssembler,
        EventBus eventBus,
        CompactionSummarizer summarizer
    ) {
        return new DefaultCompactionRuntime(
            contextAssembler,
            new DefaultCompactionCoordinator(
                sessionManager,
                contextAssembler,
                eventBus,
                DefaultCompactionRuntime.manualPlanner(),
                summarizer,
                Clock.systemUTC()
            )
        );
    }

    /**
     * 创建默认 AgentCore。
     */
    @Bean
    @ConditionalOnBean({AiProviderRuntimePort.class, ToolRuntimePort.class})
    @ConditionalOnMissingBean(AgentCorePort.class)
    public AgentCorePort agentCore(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        EventBus eventBus,
        ContextAssembler contextAssembler,
        CompactionCoordinator compactionCoordinator
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
        return new DefaultTurnExecutor(ports, TurnIds.random(), Clock.systemUTC());
    }

    /**
     * 创建默认 AgentCore factory。
     *
     * NOTE: 核心端口在创建 child turn executor 时延迟解析，避免跨配置类
     * bean 条件受装配顺序影响；缺失依赖会在使用时 fail-fast。
     */
    @Bean
    @ConditionalOnMissingBean
    public AgentCoreFactoryPort agentCoreFactory(
        ObjectProvider<AiProviderRuntimePort> aiProvider,
        ObjectProvider<ToolRuntimePort> toolRuntime,
        ObjectProvider<ToolRuntimeFactoryPort> toolRuntimeFactory,
        ObjectProvider<SecurityRuntimePort> securityRuntime,
        ObjectProvider<ResourceRuntimePort> resourceRuntime,
        EventBus eventBus,
        ObjectProvider<CompactionSummarizer> compactionSummarizer,
        Clock clock
    ) {
        return (cwd, sessionManager) -> {
            AiProviderRuntimePort resolvedAiProvider = aiProvider.getObject();
            ToolRuntimeFactoryPort resolvedToolRuntimeFactory = toolRuntimeFactory.getIfAvailable();
            ToolRuntimePort resolvedToolRuntime = resolvedToolRuntimeFactory == null
                ? toolRuntime.getObject()
                : resolvedToolRuntimeFactory.create(cwd);
            SecurityRuntimePort resolvedSecurityRuntime = securityRuntime.getObject();
            ResourceRuntimePort resolvedResourceRuntime = resourceRuntime.getObject();
            CompactionSummarizer resolvedCompactionSummarizer = compactionSummarizer.getObject();
            DefaultContextAssembler assembler = new DefaultContextAssembler(
                sessionManager,
                resolvedResourceRuntime,
                new ContextBudgetEstimator()
            );
            DefaultCompactionCoordinator compactionCoordinator = new DefaultCompactionCoordinator(
                sessionManager,
                assembler,
                eventBus,
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
                    eventBus,
                    assembler,
                    null,
                    compactionCoordinator,
                    new NoopMemoryExtractionWorker()
                ),
                TurnIds.random(),
                clock
            );
        };
    }

    /**
     * 创建默认 session runtime state。
     */
    @Bean
    @ConditionalOnMissingBean(SessionRuntimeState.class)
    public SessionRuntimeState sessionRuntimeState(LyPiRuntimeProperties properties, SessionManagerPort sessionManager) {
        var handle = sessionManager.openOrCreate(properties.getSessionId());
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

    /**
     * 创建默认启动装配服务。
     */
    @Bean
    @ConditionalOnBean(ToolRuntimePort.class)
    @ConditionalOnMissingBean(BootstrapService.class)
    public BootstrapService bootstrapService(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ToolRuntimePort toolRuntime
    ) {
        return new DefaultBootstrapService(properties, sessionManager, resourceRuntime, toolRuntime);
    }

    /**
     * 创建最小 AppEntry。
     */
    @Bean
    @ConditionalOnBean({BootstrapService.class, AgentCorePort.class})
    @ConditionalOnMissingBean(AppEntry.class)
    public AppEntry appEntry(
        BootstrapService bootstrapService,
        AgentCorePort agentCore,
        EventBus eventBus,
        LyPiRuntimeProperties properties,
        ObjectProvider<TransportLauncher> transportLaunchers
    ) {
        return new DefaultAppEntry(
            bootstrapService,
            agentCore,
            eventBus,
            properties,
            List.copyOf(transportLaunchers.orderedStream().toList())
        );
    }

    /**
     * 创建真实 TUI transport factory。
     */
    @Bean
    @ConditionalOnMissingBean
    public JLineTuiTransportFactory jLineTuiTransportFactory(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime
    ) {
        return (state, core, events, terminal, slashCommands) -> JLineTuiTransport.open(
            state,
            core,
            events,
            terminal,
            slashCommands,
            sessionManager,
            resourceRuntime,
            compactionRuntime
        );
    }

    /**
     * 创建默认 JLine TUI 启动器。
     */
    @Bean
    @ConditionalOnMissingBean(name = "jLineTuiTransportLauncher")
    public TransportLauncher jLineTuiTransportLauncher(
        JLineTuiTransportFactory factory,
        ObjectProvider<SlashCommand> slashCommands
    ) {
        return new JLineTuiTransportLauncher(factory, slashCommands.orderedStream().toList());
    }

    /**
     * 创建默认运行时聚合对象。
     */
    @Bean
    @ConditionalOnBean({AgentCorePort.class, AiProviderRuntimePort.class, ToolRuntimePort.class, BootstrapService.class})
    @ConditionalOnMissingBean(LyPiRuntime.class)
    public LyPiRuntime lyPiRuntime(
        AppEntry appEntry,
        SessionManagerPort sessionManager,
        AgentCorePort agentCore,
        AiProviderRuntimePort aiProvider,
        ToolRuntimePort toolRuntime,
        SecurityRuntimePort securityRuntime,
        ResourceRuntimePort resourceRuntime,
        CompactionRuntimePort compactionRuntime,
        ObjectProvider<TransportAdapter> transports
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
            List.copyOf(transports.orderedStream().toList())
        );
    }

    /**
     * 创建 Spring Boot 启动回调。
     */
    @Bean("lyPiApplicationRunner")
    @ConditionalOnBean(AppEntry.class)
    @ConditionalOnMissingBean(name = "lyPiApplicationRunner")
    public ApplicationRunner lyPiApplicationRunner(AppEntry appEntry, LyPiRuntimeProperties properties) {
        return args -> {
            if (isHeadlessSubagent(args)) {
                return;
            }
            appEntry.start(new cn.lypi.contracts.bootstrap.BootstrapRequest(
                properties.getCwd(),
                args == null ? List.of() : args.getNonOptionArgs(),
                Optional.of(properties.getSessionId()),
                Optional.ofNullable(properties.getInitialPrompt())
            ));
        };
    }

    private boolean isHeadlessSubagent(org.springframework.boot.ApplicationArguments args) {
        if (args == null) {
            return false;
        }
        if (args.containsOption("lypi.headless.subagent") || args.containsOption("lypi-headless-subagent")) {
            return true;
        }
        return List.of(args.getSourceArgs()).contains("headless-subagent");
    }

    /**
     * 创建 transport 事件连接器。
     */
    @Bean
    @ConditionalOnMissingBean
    public TransportEventConnector transportEventConnector(
        EventBus eventBus,
        ObjectProvider<TransportAdapter> transports,
        ObjectProvider<SessionRuntimeState> state
    ) {
        TransportEventConnector connector = new TransportEventConnector(eventBus, List.copyOf(transports.orderedStream().toList()));
        state.ifAvailable(connector::attachAll);
        return connector;
    }

    /**
     * 创建默认时钟。
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 创建 child session 服务。
     */
    @Bean
    @ConditionalOnMissingBean(ChildSessionPort.class)
    public ChildSessionPort childSessionPort() {
        return new ChildSessionService();
    }

    /**
     * 创建 child agent 持久化快照查询器。
     */
    @Bean
    @ConditionalOnMissingBean(ChildAgentSnapshotProvider.class)
    public ChildAgentSnapshotProvider childAgentSnapshotProvider(ObjectProvider<SessionManagerPort> sessionManager) {
        SessionTreeQuery query = new SessionTreeQuery(sessionStorageRoot(sessionManager.getIfAvailable()));
        return parentSessionId -> query.children(parentSessionId).stream()
            .map(this::childAgentSnapshot)
            .toList();
    }

    private ChildAgentSnapshot childAgentSnapshot(ChildSessionView child) {
        return new ChildAgentSnapshot(
            child.sessionId(),
            child.parentSessionId().orElse(""),
            child.parentSpawnEntryId().orElse(""),
            child.agentName(),
            child.agentRole()
        );
    }

    /**
     * 创建 mailbox JSONL store。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonlMailboxStore jsonlMailboxStore(ObjectProvider<SessionManagerPort> sessionManager) {
        return new JsonlMailboxStore(sessionStorageRoot(sessionManager.getIfAvailable()));
    }

    /**
     * 创建默认 mailbox 服务。
     */
    @Bean
    @ConditionalOnMissingBean(MailboxPort.class)
    public DefaultMailboxService mailboxPort(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        return new DefaultMailboxService(store, sessionManager, clock);
    }

    /**
     * 创建默认 mailbox 投递守卫。
     *
     * NOTE: 默认保守不自动投递，由 TUI/headless 空闲检测后替换。
     */
    @Bean
    @ConditionalOnMissingBean
    public MailboxDeliveryGuard mailboxDeliveryGuard(ObjectProvider<SessionRuntimeState> state, SessionManagerPort sessionManager) {
        return message -> {
            if (message == null) {
                return false;
            }
            SessionRuntimeState runtimeState = state.getIfAvailable();
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

    private boolean currentBranchContainsSpawnEntry(
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

    /**
     * 创建 mailbox 投递服务。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultMailboxService.class)
    public MailboxDeliveryService mailboxDeliveryService(DefaultMailboxService mailbox, MailboxDeliveryGuard guard) {
        return new MailboxDeliveryService(mailbox, guard);
    }

    /**
     * 创建 /mailbox slash command handler。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MailboxPort.class)
    public MailboxSlashCommandHandler mailboxSlashCommandHandler(
        MailboxPort mailbox,
        ObjectProvider<SessionRuntimeState> state,
        SessionManagerPort sessionManager
    ) {
        return new MailboxSlashCommandHandler(mailbox, () -> {
            SessionRuntimeState runtimeState = state.getIfAvailable();
            if (runtimeState != null) {
                return runtimeState.sessionId();
            }
            return sessionManager.currentView().sessionId();
        });
    }

    /**
     * 创建 /mailbox slash command 定义。
     */
    @Bean
    @ConditionalOnMissingBean(name = "mailboxSlashCommand")
    @ConditionalOnBean(MailboxSlashCommandHandler.class)
    public SlashCommand mailboxSlashCommand(MailboxSlashCommandHandler handler) {
        return handler.command();
    }

    /**
     * 创建默认 agent registry。
     */
    @Bean
    @ConditionalOnMissingBean(AgentRegistryPort.class)
    @ConditionalOnBean(DefaultMailboxService.class)
    public AgentRegistryPort agentRegistry(
        SessionManagerPort parentSession,
        DefaultMailboxService mailbox,
        ObjectProvider<RunningAgentSnapshotProvider> runningAgents,
        ObjectProvider<AgentCenterPort> agentCenter,
        ChildAgentSnapshotProvider childAgents
    ) {
        return new DefaultAgentRegistry(
            parentSession,
            mailbox,
            runningAgentSnapshotProvider(runningAgents, agentCenter),
            childAgents
        );
    }

    /**
     * 创建 /agent slash command handler。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AgentRegistryPort.class)
    public AgentSlashCommandHandler agentSlashCommandHandler(
        AgentRegistryPort registry,
        ObjectProvider<AgentCenterPort> agentCenter,
        ObjectProvider<SessionRuntimeState> state,
        SessionManagerPort sessionManager
    ) {
        return new AgentSlashCommandHandler(registry, agentCenter.getIfAvailable(), () -> {
            SessionRuntimeState runtimeState = state.getIfAvailable();
            if (runtimeState != null) {
                return runtimeState.sessionId();
            }
            return sessionManager.currentView().sessionId();
        });
    }

    /**
     * 创建 /agent slash command 定义。
     */
    @Bean
    @ConditionalOnMissingBean(name = "agentSlashCommand")
    @ConditionalOnBean(AgentSlashCommandHandler.class)
    public SlashCommand agentSlashCommand(AgentSlashCommandHandler handler) {
        return handler.command();
    }

    /**
     * 聚合 TUI slash command 定义，供真实 TUI transport 打开时接入。
     */
    @Bean
    @ConditionalOnMissingBean(name = "tuiSlashCommands")
    public List<SlashCommand> tuiSlashCommands(ObjectProvider<SlashCommand> commands) {
        return commands.orderedStream().toList();
    }

    private RunningAgentSnapshotProvider runningAgentSnapshotProvider(
        ObjectProvider<RunningAgentSnapshotProvider> runningAgents,
        ObjectProvider<AgentCenterPort> agentCenter
    ) {
        List<RunningAgentSnapshotProvider> explicitProviders = runningAgents.orderedStream()
            .filter(provider -> !(provider instanceof AgentCenterPort))
            .toList();
        if (!explicitProviders.isEmpty()) {
            return explicitProviders.getFirst();
        }
        AgentCenterPort resolvedAgentCenter = agentCenter.getIfAvailable();
        if (resolvedAgentCenter instanceof RunningAgentSnapshotProvider provider) {
            return provider;
        }
        return ignored -> List.of();
    }

    /**
     * 创建 JSON subagent 子进程 runner。
     */
    @Bean
    @ConditionalOnMissingBean
    public SubagentProcessRunner subagentProcessRunner(LyPiSubagentProperties properties) {
        return new JsonSubagentProcessRunner(properties.getCommand());
    }

    /**
     * 创建默认 agent center。
     */
    @Bean
    @ConditionalOnMissingBean(AgentCenterPort.class)
    @ConditionalOnBean({DefaultMailboxService.class, MailboxDeliveryService.class})
    public AgentCenterPort agentCenter(
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        SessionManagerFactoryPort sessionManagerFactory,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        MailboxDeliveryService deliveryService,
        LyPiSubagentProperties properties,
        Clock clock
    ) {
        return new DefaultAgentCenter(
            properties.getCommand(),
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

    private Path sessionStorageRoot(SessionManagerPort sessionManager) {
        if (sessionManager instanceof SessionStorageRootPort storageRoot) {
            return storageRoot.sessionStorageRoot();
        }
        return DEFAULT_CWD;
    }
}
