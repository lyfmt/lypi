package cn.lypi.boot.runtime;

import cn.lypi.agent.AgentCoreRuntimePorts;
import cn.lypi.agent.ContextBudgetEstimator;
import cn.lypi.agent.DefaultContextAssembler;
import cn.lypi.agent.DefaultTurnExecutor;
import cn.lypi.agent.NoopMemoryExtractionWorker;
import cn.lypi.agent.TurnIds;
import cn.lypi.agent.compact.CompactionSummarizer;
import cn.lypi.agent.compact.DefaultCompactionCoordinator;
import cn.lypi.agent.compact.DefaultCompactionPlanner;
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
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.subagent.DefaultAgentCenter;
import cn.lypi.runtime.subagent.DefaultMailboxService;
import cn.lypi.runtime.subagent.JsonSubagentProcessRunner;
import cn.lypi.runtime.subagent.JsonlMailboxStore;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.MailboxDeliveryService;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.resource.DefaultResourceRuntime;
import cn.lypi.security.DefaultPolicyEngine;
import cn.lypi.session.ChildSessionService;
import cn.lypi.session.DefaultSessionManagerFactory;
import cn.lypi.session.SessionManagerImpl;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LyPiSubagentProperties.class)
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
     * 创建默认安全策略运行时。
     */
    @Bean
    @ConditionalOnMissingBean(SecurityRuntimePort.class)
    public SecurityRuntimePort securityRuntime() {
        return new DefaultPolicyEngine();
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
     * 创建默认 session manager factory。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerFactoryPort.class)
    public SessionManagerFactoryPort sessionManagerFactory() {
        return new DefaultSessionManagerFactory();
    }

    /**
     * 创建默认父 session manager。
     *
     * NOTE: 仅构造 manager，不主动创建 session 文件；真实 session id 由上层启动流程打开。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerPort.class)
    public SessionManagerPort sessionManager() {
        return new SessionManagerImpl(DEFAULT_CWD);
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
     * 创建 mailbox JSONL store。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonlMailboxStore jsonlMailboxStore() {
        return new JsonlMailboxStore(DEFAULT_CWD);
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
            if (runtimeState == null || runtimeState.hasInterruptibleTool()) {
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
        ObjectProvider<SecurityRuntimePort> securityRuntime,
        ObjectProvider<ResourceRuntimePort> resourceRuntime,
        EventBus eventBus,
        ObjectProvider<CompactionSummarizer> compactionSummarizer,
        Clock clock
    ) {
        return (cwd, sessionManager) -> {
            AiProviderRuntimePort resolvedAiProvider = aiProvider.getObject();
            ToolRuntimePort resolvedToolRuntime = toolRuntime.getObject();
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
            processRunner,
            mailbox,
            deliveryService,
            clock
        );
    }
}
