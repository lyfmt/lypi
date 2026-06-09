package cn.lypi.boot.runtime;

import cn.lypi.agent.AgentCoreRuntimePorts;
import cn.lypi.agent.ContextBudgetEstimator;
import cn.lypi.agent.DefaultContextAssembler;
import cn.lypi.agent.DefaultTurnExecutor;
import cn.lypi.agent.NoopMemoryExtractionWorker;
import cn.lypi.agent.TurnIds;
import cn.lypi.agent.compact.NoopCompactionCoordinator;
import cn.lypi.boot.BootstrapService;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.runtime.AiProviderRuntimePort;
import cn.lypi.contracts.runtime.AppEntry;
import cn.lypi.contracts.runtime.LyPiRuntime;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.resource.DefaultResourceRuntime;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.security.DefaultPolicyEngine;
import cn.lypi.session.SessionManagerImpl;
import java.math.BigDecimal;
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
@EnableConfigurationProperties(LyPiRuntimeProperties.class)
public class LyPiRuntimeAutoConfiguration {
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
     * 创建默认资源运行时。
     */
    @Bean
    @ConditionalOnMissingBean(ResourceRuntimePort.class)
    public ResourceRuntimePort resourceRuntime() {
        return new DefaultResourceRuntime();
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
        EventBus eventBus
    ) {
        AgentCoreRuntimePorts ports = new AgentCoreRuntimePorts(
            properties.getCwd(),
            sessionManager,
            aiProvider,
            toolRuntime,
            securityRuntime,
            resourceRuntime,
            eventBus,
            new DefaultContextAssembler(sessionManager, resourceRuntime, new ContextBudgetEstimator()),
            null,
            new NoopCompactionCoordinator(),
            new NoopMemoryExtractionWorker()
        );
        return new DefaultTurnExecutor(ports, TurnIds.random(), Clock.systemUTC());
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
     * 创建默认 JLine TUI 启动器。
     */
    @Bean
    @ConditionalOnMissingBean(name = "jLineTuiTransportLauncher")
    public TransportLauncher jLineTuiTransportLauncher() {
        return new JLineTuiTransportLauncher();
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
        return args -> appEntry.start(new cn.lypi.contracts.bootstrap.BootstrapRequest(
            properties.getCwd(),
            args == null ? List.of() : args.getNonOptionArgs(),
            Optional.of(properties.getSessionId()),
            Optional.ofNullable(properties.getInitialPrompt())
        ));
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
}
