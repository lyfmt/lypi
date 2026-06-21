package cn.lypi.boot.tool;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentRegistryPort;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.hook.DefaultToolHookRuntime;
import cn.lypi.contracts.hook.ToolHook;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionProfileConfig;
import cn.lypi.contracts.security.PermissionProfileSelection;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.security.PermissionProfileConfigCompiler;
import cn.lypi.tool.BlockingPermissionGate;
import cn.lypi.tool.DefaultToolRuntime;
import cn.lypi.tool.EventPublishingPermissionGate;
import cn.lypi.tool.FilePermissionAmendmentStore;
import cn.lypi.tool.PermissionUpdateStore;
import cn.lypi.tool.FilteredToolRuntime;
import cn.lypi.tool.MemoryConsolidationToolRuntime;
import cn.lypi.tool.MemoryConsolidationWritePolicy;
import cn.lypi.tool.PermissionGate;
import cn.lypi.tool.PermissionPromptPort;
import cn.lypi.tool.PermissionResponseGate;
import cn.lypi.tool.ToolHookExecutionInterceptor;
import cn.lypi.tool.ToolRuntimeOptions;
import cn.lypi.tool.builtin.BuiltInTools;
import cn.lypi.tool.mcp.McpClientManager;
import cn.lypi.tool.mcp.McpClientManagerFactory;
import cn.lypi.tool.mcp.McpToolAdapter;
import cn.lypi.tool.mcp.McpToolResultMapper;
import cn.lypi.tool.mcp.stdio.StdioMcpClient;
import cn.lypi.tool.shell.BubblewrapExecutor;
import cn.lypi.tool.shell.ExecutorRegistry;
import cn.lypi.tool.shell.HostExecutor;
import cn.lypi.tool.shell.PermissionProfileSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import cn.lypi.transport.headless.HeadlessTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

@AutoConfiguration
@EnableConfigurationProperties({LyPiToolProperties.class, LyPiPermissionsProperties.class})
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
    public SandboxPolicyResolver sandboxPolicyResolver(
        LyPiToolProperties toolProperties,
        PermissionProfileSelection profileSelection
    ) {
        LyPiToolProperties.SandboxProperties sandbox = toolProperties.getSandbox();
        return new PermissionProfileSandboxPolicyResolver(profileSelection.permissionProfile(), new SandboxPolicyOptions(
            NetworkMode.DISABLED,
            sandbox.isFailIfUnavailable(),
            sandbox.isEnabled() && sandbox.isAutoAllowBashIfSandboxed()
        ));
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
     * 创建启动期共享的权限 profile 编译结果。
     *
     * NOTE: session runtime state 和 Bash sandbox 投影必须消费同一份编译结果。
     */
    @Bean
    @ConditionalOnMissingBean(PermissionProfileSelection.class)
    public PermissionProfileSelection permissionProfileSelection(
        LyPiToolProperties toolProperties,
        LyPiPermissionsProperties permissionsProperties,
        PermissionProfileConfigCompiler profileConfigCompiler
    ) {
        LyPiToolProperties.SandboxProperties sandbox = toolProperties.getSandbox();
        if (permissionsProperties.hasCustomProfileConfig() || sandbox.getNetworkMode() != NetworkMode.HOST) {
            return profileConfigCompiler.compile(
                permissionsProperties.profileConfigs(),
                permissionsProperties.getDefaultPermissions()
            );
        }
        PermissionProfileConfig legacyWorkspaceNetwork = new PermissionProfileConfig(
            "Legacy lypi.tool.sandbox.network-mode=host compatibility profile",
            Optional.of(":workspace"),
            List.of(),
            Optional.empty(),
            Optional.of(NetworkPermissionPolicy.enabled())
        );
        return profileConfigCompiler.compile(Map.of("legacy-workspace-network", legacyWorkspaceNetwork), "legacy-workspace-network");
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
        ObjectProvider<ToolHook> toolHooks,
        SandboxPolicyResolver sandboxPolicyResolver,
        ObjectProvider<EventBus> eventBus,
        ObjectProvider<PermissionResponseGate> responseGate,
        ObjectProvider<PermissionPromptPort> promptPort,
        ObjectProvider<ResourceRuntimePort> resourceRuntime,
        ObjectProvider<McpClientManagerFactory> mcpClientManagerFactory,
        McpClientManagerLifecycle mcpClientManagerLifecycle,
        Environment environment
    ) {
        EventBus resolvedEventBus = eventBus.getIfAvailable();
        ResourceRuntimePort resolvedResourceRuntime = resourceRuntime.getIfAvailable();
        McpClientManagerFactory resolvedMcpClientManagerFactory = mcpClientManagerFactory.getIfAvailable();
        String configuredCwd = environment.getProperty("lypi.runtime.cwd", ".");
        return new ToolRuntimeFactoryPort() {
            @Override
            public ToolRuntimePort create(Path cwd) {
                return createRuntime(cwd);
            }

            @Override
            public ToolRuntimePort create(Path cwd, SubagentToolPolicy toolPolicy) {
                ToolRuntimePort runtime = createRuntime(cwd);
                return toolPolicy == null ? runtime : new FilteredToolRuntime(runtime, toolPolicy);
            }

            @Override
            public ToolRuntimePort createMemoryConsolidation(Path cwd, EventBus eventBus) {
                Path runtimeCwd = cwd == null ? Path.of(configuredCwd) : cwd;
                ToolRuntimePort runtime = createRuntime(runtimeCwd, eventBus, denyResponseGate(), null);
                return new MemoryConsolidationToolRuntime(
                    runtime,
                    new MemoryConsolidationWritePolicy(runtimeCwd)
                );
            }

            private ToolRuntimePort createRuntime(Path cwd) {
                return createRuntime(cwd, resolvedEventBus, responseGate.getIfAvailable(), promptPort.getIfAvailable());
            }

            private ToolRuntimePort createRuntime(
                Path cwd,
                EventBus runtimeEventBus,
                PermissionResponseGate runtimeResponseGate,
                PermissionPromptPort runtimePromptPort
            ) {
                Path runtimeCwd = cwd == null ? Path.of(configuredCwd) : cwd;
                ToolRuntimeOptions options = ToolRuntimeOptions.builder()
                    .cwd(runtimeCwd)
                    .build();
                DefaultToolRuntime runtime = toolRuntime(
                    runtimeEventBus,
                    new cn.lypi.tool.DefaultToolRegistry(),
                    new cn.lypi.tool.ToolSchemaValidator(),
                    new cn.lypi.tool.ToolExecutionPlanner(),
                    new cn.lypi.tool.ToolResultBudgeter(),
                    new cn.lypi.tool.ToolRuntimeContextFactory(options),
                    new ToolHookExecutionInterceptor(new DefaultToolHookRuntime(toolHooks.orderedStream().toList())),
                    securityRuntime,
                    runtimeResponseGate,
                    runtimePromptPort,
                    new FilePermissionAmendmentStore(runtimeCwd)
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
                registerMcpTools(runtime, runtimeCwd, resolvedResourceRuntime, resolvedMcpClientManagerFactory, mcpClientManagerLifecycle);
                return runtime;
            }

            private PermissionResponseGate denyResponseGate() {
                return requestEvent -> new PermissionResponse(
                    requestEvent.sessionId(),
                    requestEvent.requestId(),
                    "deny",
                    false,
                    Instant.now()
                );
            }
        };
    }

    /**
     * 创建 MCP client manager 生命周期管理器。
     */
    @Bean
    @ConditionalOnMissingBean(McpClientManagerLifecycle.class)
    public McpClientManagerLifecycle mcpClientManagerLifecycle() {
        return new McpClientManagerLifecycle();
    }

    /**
     * 创建默认 MCP client manager factory。
     */
    @Bean
    @ConditionalOnMissingBean(McpClientManagerFactory.class)
    public McpClientManagerFactory mcpClientManagerFactory(ObjectProvider<ObjectMapper> objectMapper) {
        ObjectMapper resolvedObjectMapper = objectMapper.getIfAvailable(ObjectMapper::new);
        return cwd -> new McpClientManager(
            cwd,
            (config, runtimeCwd) -> {
                if (config.transport() != McpTransport.STDIO) {
                    throw new IllegalArgumentException("Unsupported MCP transport: " + config.transport());
                }
                return new StdioMcpClient(config, runtimeCwd, resolvedObjectMapper, message -> {
                });
            },
            new McpToolResultMapper(resolvedObjectMapper)
        );
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
        PermissionUpdateStore permissionUpdateStore
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

    private void registerMcpTools(
        ToolRuntimePort runtime,
        Path cwd,
        ResourceRuntimePort resourceRuntime,
        McpClientManagerFactory managerFactory,
        McpClientManagerLifecycle managerLifecycle
    ) {
        if (resourceRuntime == null || managerFactory == null) {
            return;
        }
        try {
            cn.lypi.contracts.resource.ResourceSnapshot resources = resourceRuntime.load(cwd);
            if (resources == null || resources.mcpServers() == null || resources.mcpServers().isEmpty()) {
                return;
            }
            McpClientManager manager = managerFactory.create(cwd);
            managerLifecycle.track(manager);
            manager.connectAll(resources.mcpServers()).forEach(schema ->
                runtime.register(new McpToolAdapter(schema, manager::invoke))
            );
        } catch (RuntimeException exception) {
            // NOTE: MCP 注册失败不能阻断内置工具可用性。
        }
    }
}
