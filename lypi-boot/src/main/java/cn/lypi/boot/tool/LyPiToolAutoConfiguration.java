package cn.lypi.boot.tool;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.tool.BlockingPermissionGate;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.EventPublishingPermissionGate;
import cn.lypi.tool.FilePermissionUpdateStore;
import cn.lypi.tool.PermissionGate;
import cn.lypi.tool.PermissionPromptPort;
import cn.lypi.tool.PermissionResponseGate;
import cn.lypi.tool.ToolRuntimeOptions;
import cn.lypi.tool.builtin.BuiltInTools;
import cn.lypi.tool.shell.BubblewrapExecutor;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.ExecutorRegistry;
import cn.lypi.tool.shell.HostExecutor;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import cn.lypi.transport.headless.HeadlessTransport;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties(LyPiToolProperties.class)
public class LyPiToolAutoConfiguration {
    /**
     * 创建默认宿主机命令执行器。
     */
    @Bean
    @ConditionalOnMissingBean(Executor.class)
    public HostExecutor hostExecutor() {
        return new HostExecutor();
    }

    /**
     * 创建默认 Bubblewrap 命令执行器。
     */
    @Bean
    @ConditionalOnBean(HostExecutor.class)
    @ConditionalOnMissingBean(BubblewrapExecutor.class)
    public BubblewrapExecutor bubblewrapExecutor(HostExecutor hostExecutor) {
        return new BubblewrapExecutor(hostExecutor);
    }

    /**
     * 创建默认沙盒策略解析器。
     */
    @Bean
    @ConditionalOnMissingBean(SandboxPolicyResolver.class)
    public SandboxPolicyResolver sandboxPolicyResolver(LyPiToolProperties properties) {
        LyPiToolProperties.SandboxProperties sandbox = properties.getSandbox();
        return new DefaultSandboxPolicyResolver(new SandboxPolicyOptions(
            sandbox.getNetworkMode(),
            sandbox.isFailIfUnavailable(),
            sandbox.isEnabled() && sandbox.isAutoAllowBashIfSandboxed()
        ));
    }

    /**
     * 创建默认执行器注册表。
     */
    @Bean
    @Primary
    @ConditionalOnBean({HostExecutor.class, BubblewrapExecutor.class})
    @ConditionalOnMissingBean(value = Executor.class, ignored = {HostExecutor.class, BubblewrapExecutor.class})
    public ExecutorRegistry executorRegistry(
        HostExecutor hostExecutor,
        BubblewrapExecutor bubblewrapExecutor,
        LyPiToolProperties properties
    ) {
        return new ExecutorRegistry(hostExecutor, bubblewrapExecutor, properties.getSandbox().isEnabled());
    }

    /**
     * 创建工具运行时。
     *
     * NOTE: 缺少本地权限 prompt 时保持 fail-safe deny；存在 prompt 和事件总线时，
     * 使用事件装饰 gate 发布权限请求和决策事件。
     */
    @Bean
    @ConditionalOnMissingBean({ToolRuntimeFactoryPort.class, ToolRuntimePort.class})
    public ToolRuntimeFactoryPort toolRuntimeFactory(
        SecurityRuntimePort securityRuntime,
        Executor executor,
        ObjectProvider<AgentCenterPort> agentCenter,
        ObjectProvider<MailboxPort> mailbox,
        ObjectProvider<AgentRegistryPort> agentRegistry,
        SandboxPolicyResolver sandboxPolicyResolver,
        ObjectProvider<EventBus> eventBus,
        ObjectProvider<PermissionResponseGate> responseGate,
        ObjectProvider<PermissionPromptPort> promptPort,
        Environment environment
    ) {
        EventBus resolvedEventBus = eventBus.getIfAvailable();
        String configuredCwd = environment.getProperty("lypi.runtime.cwd", ".");
        return cwd -> {
            Path runtimeCwd = cwd == null ? Path.of(configuredCwd) : cwd;
            ToolRuntimeOptions options = ToolRuntimeOptions.builder()
                .cwd(runtimeCwd)
                .build();
            DefaultToolRuntime runtime = toolRuntime(
                resolvedEventBus,
                new cn.lypi.tool.DefaultToolRegistry(),
                new cn.lypi.tool.ToolSchemaValidator(),
                new cn.lypi.tool.ToolExecutionPlanner(),
                new cn.lypi.tool.ToolResultBudgeter(),
                new cn.lypi.tool.ToolRuntimeContextFactory(options),
                cn.lypi.tool.ToolExecutionInterceptors.noop(),
                securityRuntime,
                responseGate.getIfAvailable(),
                promptPort.getIfAvailable(),
                new FilePermissionUpdateStore(runtimeCwd)
            );
            BuiltInTools.registerDefaults(runtime, executor, sandboxPolicyResolver);
            AgentCenterPort resolvedAgentCenter = agentCenter.getIfAvailable();
            MailboxPort resolvedMailbox = mailbox.getIfAvailable();
            if (resolvedAgentCenter != null && resolvedMailbox != null) {
                AgentRegistryPort resolvedAgentRegistry = agentRegistry.getIfAvailable();
                if (resolvedAgentRegistry == null) {
                    BuiltInTools.registerSubagentTools(runtime, resolvedAgentCenter, resolvedMailbox);
                } else {
                    BuiltInTools.registerSubagentTools(runtime, resolvedAgentCenter, resolvedMailbox, resolvedAgentRegistry);
                }
            }
            return runtime;
        };
    }

    /**
     * 创建工具运行时。
     */
    @Bean
    @ConditionalOnMissingBean(ToolRuntimePort.class)
    public ToolRuntimePort toolRuntime(ToolRuntimeFactoryPort factory, Environment environment) {
        String configuredCwd = environment.getProperty("lypi.runtime.cwd", ".");
        return factory.create(Path.of(configuredCwd));
    }

    /**
     * 创建事件总线权限响应 gate。
     */
    @Bean
    @ConditionalOnMissingBean({PermissionResponseGate.class, PermissionPromptPort.class})
    public PermissionResponseGate permissionResponseGate(
        ObjectProvider<EventBus> eventBus,
        ObjectProvider<HeadlessTransport> headlessTransports,
        Environment environment
    ) {
        if (headlessTransports.stream().findAny().isPresent() || !"tui".equalsIgnoreCase(environment.getProperty("lypi.runtime.transport", "headless"))) {
            return requestEvent -> new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                "deny",
                false,
                Instant.now()
            );
        }
        EventBus resolvedEventBus = eventBus.getIfAvailable();
        if (resolvedEventBus == null) {
            return requestEvent -> new PermissionResponse(
                requestEvent.sessionId(),
                requestEvent.requestId(),
                requestEvent.cancelOptionId(),
                true,
                Instant.now()
            );
        }
        return new EventBusPermissionResponseGate(resolvedEventBus);
    }

    private DefaultToolRuntime toolRuntime(
        EventBus eventBus,
        cn.lypi.tool.DefaultToolRegistry registry,
        cn.lypi.tool.ToolSchemaValidator schemaValidator,
        cn.lypi.tool.ToolExecutionPlanner executionPlanner,
        cn.lypi.tool.ToolResultBudgeter resultBudgeter,
        cn.lypi.tool.ToolRuntimeContextFactory contextFactory,
        cn.lypi.tool.ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionResponseGate responseGate,
        PermissionPromptPort promptPort,
        FilePermissionUpdateStore permissionUpdateStore
    ) {
        if (eventBus != null && responseGate != null) {
            return new DefaultToolRuntime(
                registry,
                schemaValidator,
                executionPlanner,
                resultBudgeter,
                contextFactory,
                interceptor,
                securityRuntime,
                responseGate,
                eventBus,
                permissionUpdateStore
            );
        }
        return new DefaultToolRuntime(
            registry,
            schemaValidator,
            executionPlanner,
            resultBudgeter,
            contextFactory,
            interceptor,
            securityRuntime,
            permissionGate(eventBus, promptPort),
            eventBus,
            permissionUpdateStore
        );
    }

    private PermissionGate permissionGate(EventBus eventBus, PermissionPromptPort promptPort) {
        if (promptPort == null) {
            return PermissionGate.denying();
        }
        PermissionGate blockingGate = new BlockingPermissionGate(promptPort);
        if (eventBus == null) {
            return blockingGate;
        }
        return new EventPublishingPermissionGate(eventBus, blockingGate);
    }
}
