package cn.lypi.boot.runtime;

import cn.lypi.agent.ContextAssembler;
import cn.lypi.agent.compact.CompactionCoordinator;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.boot.BootstrapService;
import cn.lypi.boot.tool.LyPiPermissionsProperties;
import cn.lypi.boot.tool.ToolRuntimeFactoryPort;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.model.ModelCatalogPort;
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
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.DiffViewProvider;
import cn.lypi.contracts.tui.NewSessionController;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SlashCommand;
import cn.lypi.runtime.memory.MemoryConsolidationAuditSink;
import cn.lypi.runtime.memory.MemoryConsolidationPromptFactory;
import cn.lypi.runtime.memory.MemoryConsolidationRunner;
import cn.lypi.runtime.memory.MemoryConsolidationTrigger;
import cn.lypi.runtime.memory.MemoryConsolidationTurnEndListener;
import cn.lypi.runtime.subagent.ChildAgentSnapshotProvider;
import cn.lypi.runtime.subagent.DefaultMailboxService;
import cn.lypi.runtime.subagent.JsonlMailboxStore;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.MailboxDeliveryService;
import cn.lypi.runtime.subagent.RunningAgentSnapshotProvider;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.security.PermissionProfileConfigCompiler;
import cn.lypi.transport.tui.AgentSlashCommandHandler;
import cn.lypi.transport.tui.JLineTuiTransportFactory;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutorService;
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
@EnableConfigurationProperties({
    LyPiRuntimeProperties.class,
    LyPiSubagentProperties.class,
    LyPiPermissionsProperties.class
})
public class LyPiRuntimeAutoConfiguration {
    /**
     * 创建默认事件总线。
     */
    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus eventBus() {
        return RuntimeBeanFactories.eventBus();
    }

    /**
     * 创建默认权限策略运行时。
     */
    @Bean
    @ConditionalOnMissingBean(SecurityRuntimePort.class)
    public SecurityRuntimePort securityRuntime(LyPiRuntimeProperties properties) {
        return RuntimeBeanFactories.securityRuntime(properties);
    }

    /**
     * 创建权限 profile 配置编译器。
     */
    @Bean
    @ConditionalOnMissingBean(PermissionProfileConfigCompiler.class)
    public PermissionProfileConfigCompiler permissionProfileConfigCompiler() {
        return new PermissionProfileConfigCompiler();
    }

    /**
     * 创建默认 session 管理器。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerPort.class)
    public SessionManagerPort sessionManager(
        LyPiRuntimeProperties properties,
        LyPiPermissionsProperties permissionsProperties,
        PermissionProfileConfigCompiler profileConfigCompiler
    ) {
        return RuntimeBeanFactories.sessionManager(properties, permissionsProperties, profileConfigCompiler);
    }

    /**
     * 创建默认 session manager factory。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerFactoryPort.class)
    public SessionManagerFactoryPort sessionManagerFactory() {
        return RuntimeBeanFactories.sessionManagerFactory();
    }

    /**
     * 创建默认资源运行时。
     */
    @Bean
    @ConditionalOnMissingBean(ResourceRuntimePort.class)
    public ResourceRuntimePort resourceRuntime() {
        return RuntimeBeanFactories.resourceRuntime();
    }

    /**
     * 创建默认 context assembler。
     */
    @Bean
    @ConditionalOnMissingBean(ContextAssembler.class)
    public ContextAssembler contextAssembler(
        SessionManagerPort sessionManager,
        ResourceRuntimePort resourceRuntime,
        ObjectProvider<ModelCatalogPort> modelCatalog
    ) {
        return RuntimeBeanFactories.contextAssembler(sessionManager, resourceRuntime, modelCatalog.getIfAvailable());
    }

    /**
     * 创建缺省 compact summary 占位实现。
     *
     * NOTE: 完整应用由 LyPiAiAutoConfiguration 提供使用主模型的 AiCompactionSummarizer；
     * 这里仅让未加载 AI 自动配置的精简装配在使用 compact 时 fail-fast。
     */
    @Bean
    @ConditionalOnMissingBean(CompactionSummarizer.class)
    public CompactionSummarizer compactionSummarizer() {
        return RuntimeBeanFactories.unavailableCompactionSummarizer();
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
        return RuntimeBeanFactories.compactionCoordinator(
            sessionManager,
            contextAssembler,
            eventBus,
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
        return RuntimeBeanFactories.compactionRuntime(
            sessionManager,
            contextAssembler,
            eventBus,
            summarizer,
            Clock.systemUTC()
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
        return RuntimeBeanFactories.agentCore(
            properties,
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            contextAssembler,
            compactionCoordinator,
            Clock.systemUTC()
        );
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
        ObjectProvider<ModelCatalogPort> modelCatalog,
        Clock clock
    ) {
        return RuntimeBeanFactories.agentCoreFactory(
            aiProvider,
            toolRuntime,
            toolRuntimeFactory,
            securityRuntime,
            resourceRuntime,
            eventBus,
            compactionSummarizer,
            modelCatalog,
            clock
        );
    }

    /**
     * 创建后台记忆沉淀触发器。
     */
    @Bean
    @ConditionalOnMissingBean
    public MemoryConsolidationTrigger memoryConsolidationTrigger() {
        return RuntimeBeanFactories.memoryConsolidationTrigger();
    }

    /**
     * 创建后台记忆沉淀 prompt factory。
     */
    @Bean
    @ConditionalOnMissingBean
    public MemoryConsolidationPromptFactory memoryConsolidationPromptFactory() {
        return RuntimeBeanFactories.memoryConsolidationPromptFactory();
    }

    /**
     * 创建后台记忆沉淀审计 sink。
     */
    @Bean
    @ConditionalOnMissingBean
    public MemoryConsolidationAuditSink memoryConsolidationAuditSink(LyPiRuntimeProperties properties) {
        return RuntimeBeanFactories.memoryConsolidationAuditSink(properties);
    }

    /**
     * 创建后台记忆沉淀 executor。
     */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = "memoryConsolidationExecutor")
    public ExecutorService memoryConsolidationExecutor() {
        return RuntimeBeanFactories.memoryConsolidationExecutor();
    }

    /**
     * 创建后台记忆沉淀 runner。
     */
    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationRunner.class)
    @ConditionalOnBean({AgentCoreFactoryPort.class, ToolRuntimeFactoryPort.class})
    public MemoryConsolidationRunner memoryConsolidationRunner(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        AgentCoreFactoryPort agentCoreFactory,
        ToolRuntimeFactoryPort toolRuntimeFactory,
        MemoryConsolidationPromptFactory promptFactory,
        MemoryConsolidationAuditSink auditSink
    ) {
        return RuntimeBeanFactories.memoryConsolidationRunner(
            properties,
            sessionManager,
            agentCoreFactory,
            toolRuntimeFactory,
            promptFactory,
            auditSink
        );
    }

    /**
     * 创建 turn end 后台记忆沉淀监听器。
     */
    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(MemoryConsolidationRunner.class)
    public MemoryConsolidationTurnEndListener memoryConsolidationTurnEndListener(
        EventBus eventBus,
        SessionManagerPort sessionManager,
        MemoryConsolidationTrigger trigger,
        MemoryConsolidationRunner runner,
        java.util.concurrent.Executor executor,
        MemoryConsolidationAuditSink auditSink
    ) {
        return RuntimeBeanFactories.memoryConsolidationTurnEndListener(
            eventBus,
            sessionManager,
            trigger,
            runner,
            executor,
            auditSink
        );
    }

    /**
     * 创建默认 session runtime state。
     */
    @Bean
    @ConditionalOnMissingBean(SessionRuntimeState.class)
    public SessionRuntimeState sessionRuntimeState(LyPiRuntimeProperties properties, SessionManagerPort sessionManager) {
        return RuntimeBeanFactories.sessionRuntimeState(properties, sessionManager);
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
        return RuntimeBeanFactories.bootstrapService(properties, sessionManager, resourceRuntime, toolRuntime);
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
        SessionManagerPort sessionManager,
        ObjectProvider<TransportLauncher> transportLaunchers
    ) {
        return RuntimeBeanFactories.appEntry(
            bootstrapService,
            agentCore,
            eventBus,
            properties,
            sessionManager,
            transportLaunchers.orderedStream().toList()
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
        return RuntimeBeanFactories.jLineTuiTransportFactory(sessionManager, resourceRuntime, compactionRuntime);
    }

    /**
     * 创建默认 resume session 控制器。
     */
    @Bean
    @ConditionalOnMissingBean
    public ResumeSessionController resumeSessionController(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        EventBus eventBus,
        ObjectProvider<AiProviderRuntimePort> aiProviderRuntime
    ) {
        return RuntimeBeanFactories.resumeSessionController(
            properties,
            sessionManager,
            eventBus,
            aiProviderRuntime.getIfAvailable()
        );
    }

    /**
     * 创建默认 new session 控制器。
     */
    @Bean
    @ConditionalOnMissingBean
    public NewSessionController newSessionController(
        LyPiRuntimeProperties properties,
        SessionManagerPort sessionManager,
        EventBus eventBus
    ) {
        return RuntimeBeanFactories.newSessionController(properties, sessionManager, eventBus);
    }

    /**
     * 创建默认 Git working tree diff provider。
     */
    @Bean
    @ConditionalOnMissingBean
    public DiffViewProvider diffViewProvider() {
        return RuntimeBeanFactories.diffViewProvider();
    }

    /**
     * 创建默认 JLine TUI 启动器。
     */
    @Bean
    @ConditionalOnMissingBean(name = "jLineTuiTransportLauncher")
    public TransportLauncher jLineTuiTransportLauncher(
        JLineTuiTransportFactory factory,
        DiffViewProvider diffViewProvider,
        ResumeSessionController resumeController,
        NewSessionController newSessionController,
        ObjectProvider<SlashCommand> slashCommands
    ) {
        return RuntimeBeanFactories.jLineTuiTransportLauncher(
            factory,
            diffViewProvider,
            resumeController,
            newSessionController,
            slashCommands.orderedStream().toList()
        );
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
        return RuntimeBeanFactories.lyPiRuntime(
            appEntry,
            sessionManager,
            agentCore,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            compactionRuntime,
            transports.orderedStream().toList()
        );
    }

    /**
     * 创建 Spring Boot 启动回调。
     */
    @Bean("lyPiApplicationRunner")
    @ConditionalOnBean(AppEntry.class)
    @ConditionalOnMissingBean(name = "lyPiApplicationRunner")
    public ApplicationRunner lyPiApplicationRunner(AppEntry appEntry, LyPiRuntimeProperties properties) {
        return RuntimeBeanFactories.applicationRunner(appEntry, properties);
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
        return RuntimeBeanFactories.transportEventConnector(
            eventBus,
            transports.orderedStream().toList(),
            state.getIfAvailable()
        );
    }

    /**
     * 创建默认时钟。
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return RuntimeBeanFactories.clock();
    }

    /**
     * 创建 child session 服务。
     */
    @Bean
    @ConditionalOnMissingBean(ChildSessionPort.class)
    public ChildSessionPort childSessionPort() {
        return RuntimeBeanFactories.childSessionPort();
    }

    /**
     * 创建 child agent 持久化快照查询器。
     */
    @Bean
    @ConditionalOnMissingBean(ChildAgentSnapshotProvider.class)
    public ChildAgentSnapshotProvider childAgentSnapshotProvider(ObjectProvider<SessionManagerPort> sessionManager) {
        return RuntimeBeanFactories.childAgentSnapshotProvider(sessionManager.getIfAvailable());
    }

    /**
     * 创建 mailbox JSONL store。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonlMailboxStore jsonlMailboxStore(ObjectProvider<SessionManagerPort> sessionManager) {
        return RuntimeBeanFactories.jsonlMailboxStore(sessionManager.getIfAvailable());
    }

    /**
     * 创建默认 mailbox 服务。
     */
    @Bean
    @ConditionalOnMissingBean(MailboxPort.class)
    public DefaultMailboxService mailboxPort(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        return RuntimeBeanFactories.mailboxPort(store, sessionManager, clock);
    }

    /**
     * 创建默认 mailbox 投递守卫。
     *
     * NOTE: 默认保守不自动投递，由 TUI/headless 空闲检测后替换。
     */
    @Bean
    @ConditionalOnMissingBean
    public MailboxDeliveryGuard mailboxDeliveryGuard(ObjectProvider<SessionRuntimeState> state, SessionManagerPort sessionManager) {
        return RuntimeBeanFactories.mailboxDeliveryGuard(state::getIfAvailable, sessionManager);
    }

    /**
     * 创建 mailbox 投递服务。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultMailboxService.class)
    public MailboxDeliveryService mailboxDeliveryService(DefaultMailboxService mailbox, MailboxDeliveryGuard guard) {
        return RuntimeBeanFactories.mailboxDeliveryService(mailbox, guard);
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
        return RuntimeBeanFactories.mailboxSlashCommandHandler(mailbox, state::getIfAvailable, sessionManager);
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
        return RuntimeBeanFactories.agentRegistry(
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
        return RuntimeBeanFactories.agentSlashCommandHandler(
            registry,
            agentCenter.getIfAvailable(),
            state::getIfAvailable,
            sessionManager
        );
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
        return RuntimeBeanFactories.tuiSlashCommands(commands.orderedStream().toList());
    }

    private RunningAgentSnapshotProvider runningAgentSnapshotProvider(
        ObjectProvider<RunningAgentSnapshotProvider> runningAgents,
        ObjectProvider<AgentCenterPort> agentCenter
    ) {
        return RuntimeBeanFactories.runningAgentSnapshotProvider(
            runningAgents.orderedStream().toList(),
            agentCenter.getIfAvailable()
        );
    }

    /**
     * 创建 subagent 子进程命令解析器。
     */
    @Bean
    @ConditionalOnMissingBean
    SubagentCommandResolver subagentCommandResolver(LyPiSubagentProperties properties) {
        return RuntimeBeanFactories.subagentCommandResolver(properties);
    }

    /**
     * 创建 JSON subagent 子进程 runner。
     */
    @Bean
    @ConditionalOnMissingBean
    public SubagentProcessRunner subagentProcessRunner(SubagentCommandResolver subagentCommandResolver) {
        return RuntimeBeanFactories.subagentProcessRunner(subagentCommandResolver);
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
        SubagentCommandResolver subagentCommandResolver,
        Clock clock
    ) {
        return RuntimeBeanFactories.agentCenter(
            childSessions,
            parentSession,
            sessionManagerFactory,
            processRunner,
            mailbox,
            deliveryService,
            subagentCommandResolver,
            clock
        );
    }

}
