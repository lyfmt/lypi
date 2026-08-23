package cn.lycode.boot.tool;

import cn.lycode.contracts.runtime.AgentCenterPort;
import cn.lycode.contracts.runtime.AiProviderRuntimePort;
import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.mcp.McpTransport;
import cn.lycode.contracts.resource.ResourceSnapshot;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.runtime.ResourceRuntimePort;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.security.NetworkPermissionPolicy;
import cn.lycode.contracts.security.PermissionProfileConfig;
import cn.lycode.contracts.security.PermissionProfileSelection;
import cn.lycode.contracts.security.PermissionResponse;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.security.PermissionProfileConfigCompiler;
import cn.lycode.tool.BlockingPermissionGate;
import cn.lycode.tool.DefaultToolRuntime;
import cn.lycode.tool.EventPublishingPermissionGate;
import cn.lycode.tool.FilePermissionAmendmentStore;
import cn.lycode.tool.PermissionUpdateStore;
import cn.lycode.tool.FilteredToolRuntime;
import cn.lycode.tool.MemoryConsolidationToolRuntime;
import cn.lycode.tool.MemoryConsolidationWritePolicy;
import cn.lycode.tool.ModelPermissionReviewer;
import cn.lycode.tool.PermissionGate;
import cn.lycode.tool.PermissionPromptPort;
import cn.lycode.tool.PermissionReviewer;
import cn.lycode.tool.PermissionResponseGate;
import cn.lycode.tool.ToolRuntimeOptions;
import cn.lycode.tool.builtin.BuiltInTools;
import cn.lycode.tool.builtin.ShellEnvironmentHarness;
import cn.lycode.tool.mcp.McpClientManager;
import cn.lycode.tool.mcp.McpClientManagerFactory;
import cn.lycode.tool.mcp.McpToolAdapter;
import cn.lycode.tool.mcp.McpToolResultMapper;
import cn.lycode.tool.mcp.stdio.StdioMcpClient;
import cn.lycode.tool.shell.BubblewrapExecutor;
import cn.lycode.tool.shell.ExecutorRegistry;
import cn.lycode.tool.shell.HostExecutor;
import cn.lycode.tool.shell.PermissionProfileSandboxPolicyResolver;
import cn.lycode.tool.shell.SandboxPolicyOptions;
import cn.lycode.tool.shell.SandboxPolicyResolver;
import cn.lycode.tool.web.BraveWebSearchProvider;
import cn.lycode.tool.web.ExaWebSearchProvider;
import cn.lycode.tool.web.FileWebResultStore;
import cn.lycode.tool.web.JavaHttpWebClient;
import cn.lycode.tool.web.PerplexityWebSearchProvider;
import cn.lycode.tool.web.TavilyWebProvider;
import cn.lycode.tool.web.WebProviderRegistry;
import cn.lycode.tool.web.WebResultStore;
import cn.lycode.tool.web.WebSearchProvider;
import cn.lycode.transport.headless.HeadlessTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
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
@EnableConfigurationProperties({LyCodeToolProperties.class, LyCodePermissionsProperties.class, LyCodeWebProperties.class})
public class LyCodeToolAutoConfiguration {
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
        LyCodeToolProperties toolProperties,
        LyCodePermissionsProperties permissionsProperties,
        PermissionProfileSelection profileSelection
    ) {
        LyCodeToolProperties.SandboxProperties sandbox = toolProperties.getSandbox();
        boolean configuredProfileOverridesRuntimeState = permissionsProperties.hasExplicitProfileConfig()
            || !":workspace".equals(profileSelection.activePermissionProfile().id());
        return new PermissionProfileSandboxPolicyResolver(profileSelection.permissionProfile(), new SandboxPolicyOptions(
            NetworkMode.DISABLED,
            sandbox.isFailIfUnavailable(),
            sandbox.isEnabled() && sandbox.isAutoAllowBashIfSandboxed()
        ), configuredProfileOverridesRuntimeState);
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
        LyCodeToolProperties toolProperties,
        LyCodePermissionsProperties permissionsProperties,
        PermissionProfileConfigCompiler profileConfigCompiler
    ) {
        LyCodeToolProperties.SandboxProperties sandbox = toolProperties.getSandbox();
        if (permissionsProperties.hasExplicitProfileConfig() || sandbox.getNetworkMode() != NetworkMode.HOST) {
            return profileConfigCompiler.compile(
                permissionsProperties.profileConfigs(),
                permissionsProperties.getDefaultPermissions()
            );
        }
        PermissionProfileConfig legacyWorkspaceNetwork = new PermissionProfileConfig(
            "Legacy lycode.tool.sandbox.network-mode=host compatibility profile",
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
        LyCodeToolProperties properties
    ) {
        return new ExecutorRegistry(hostExecutor, bubblewrapExecutor, properties.getSandbox().isEnabled());
    }

    /**
     * 创建跨主代理和子代理 runtime 共享的 shell 状态 harness。
     */
    @Bean
    @ConditionalOnMissingBean(ShellEnvironmentHarness.class)
    public ShellEnvironmentHarness shellEnvironmentHarness(LyCodeToolProperties properties) {
        return new ShellEnvironmentHarness(properties.getShell().getStateRoot());
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
        SandboxPolicyResolver sandboxPolicyResolver,
        ShellEnvironmentHarness shellEnvironmentHarness,
        ObjectProvider<EventBus> eventBus,
        ObjectProvider<PermissionResponseGate> responseGate,
        ObjectProvider<PermissionPromptPort> promptPort,
        ObjectProvider<AiProviderRuntimePort> aiProvider,
        ObjectProvider<ResourceRuntimePort> resourceRuntime,
        ObjectProvider<McpClientManagerFactory> mcpClientManagerFactory,
        McpClientManagerLifecycle mcpClientManagerLifecycle,
        LyCodeWebProperties webProperties,
        ObjectProvider<ObjectMapper> objectMapper,
        Environment environment
    ) {
        EventBus resolvedEventBus = eventBus.getIfAvailable();
        ResourceRuntimePort resolvedResourceRuntime = resourceRuntime.getIfAvailable();
        McpClientManagerFactory resolvedMcpClientManagerFactory = mcpClientManagerFactory.getIfAvailable();
        ObjectMapper resolvedObjectMapper = objectMapper.getIfAvailable(ObjectMapper::new);
        AiProviderRuntimePort resolvedAiProvider = aiProvider.getIfAvailable();
        PermissionReviewer permissionReviewer = resolvedAiProvider == null
            ? PermissionReviewer.denying()
            : new ModelPermissionReviewer(resolvedAiProvider);
        String configuredCwd = environment.getProperty("lycode.runtime.cwd", ".");
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
                ResourceSnapshot resources = loadResources(resolvedResourceRuntime, runtimeCwd);
                ToolRuntimeOptions options = ToolRuntimeOptions.builder()
                    .cwd(runtimeCwd)
                    .build();
                DefaultToolRuntime runtime = toolRuntime(
                    runtimeEventBus,
                    new cn.lycode.tool.DefaultToolRegistry(),
                    new cn.lycode.tool.ToolSchemaValidator(),
                    new cn.lycode.tool.ToolExecutionPlanner(),
                    new cn.lycode.tool.ToolResultBudgeter(),
                    new cn.lycode.tool.ToolRuntimeContextFactory(options),
                    cn.lycode.tool.ToolExecutionInterceptors.noop(),
                    securityRuntime,
                    runtimeResponseGate,
                    runtimePromptPort,
                    new FilePermissionAmendmentStore(runtimeCwd),
                    permissionReviewer
                );
                BuiltInTools.registerDefaults(runtime, executor, sandboxPolicyResolver, shellEnvironmentHarness);
                WebResultStore webResultStore = webResultStore(webProperties, runtimeCwd);
                if (webProperties.isEnabled()) {
                    registerWebFetchTool(runtime, webProperties, webResultStore);
                    BuiltInTools.registerWebContentTools(runtime, webResultStore);
                }
                webProviderRegistry(webProperties, resolvedObjectMapper, environment)
                    .ifPresent(providers ->
                        BuiltInTools.registerWebSearchTools(runtime, providers, webResultStore, webProperties.getMaxResults())
                    );
                AgentCenterPort resolvedAgentCenter = agentCenter.getIfAvailable();
                if (resolvedAgentCenter != null) {
                    BuiltInTools.registerSubagentTools(
                        runtime,
                        resolvedAgentCenter,
                        resources == null ? List.of() : resources.expertAgents()
                    );
                }
                registerMcpTools(
                    runtime,
                    runtimeCwd,
                    resources,
                    resolvedMcpClientManagerFactory,
                    mcpClientManagerLifecycle
                );
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

    private WebResultStore webResultStore(LyCodeWebProperties properties, Path runtimeCwd) {
        if (properties != null && properties.getCache() != null && !properties.getCache().isEnabled()) {
            return WebResultStore.disabled("Web 结果缓存未启用。请启用 lycode.web.cache.enabled=true 后再取回内容。");
        }
        return new FileWebResultStore(runtimeCwd);
    }

    private void registerWebFetchTool(ToolRuntimePort runtime, LyCodeWebProperties properties, WebResultStore webResultStore) {
        LyCodeWebProperties.FetchProperties fetch = properties.getFetch();
        LyCodeWebProperties.JinaProperties jina = fetch.getJina();
        LyCodeWebProperties.FallbackProperties fallback = fetch.getFallback();
        BuiltInTools.registerWebFetchTool(
            runtime,
            properties.getTimeout(),
            fallback.isEnabled() && jina.isEnabled(),
            jina.getEndpoint(),
            fallback.getMinBodyChars(),
            webResultStore
        );
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
        String configuredCwd = environment.getProperty("lycode.runtime.cwd", ".");
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
        if (headlessTransports.stream().findAny().isPresent() || !"tui".equalsIgnoreCase(environment.getProperty("lycode.runtime.transport", "headless"))) {
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
        cn.lycode.tool.DefaultToolRegistry registry,
        cn.lycode.tool.ToolSchemaValidator schemaValidator,
        cn.lycode.tool.ToolExecutionPlanner executionPlanner,
        cn.lycode.tool.ToolResultBudgeter resultBudgeter,
        cn.lycode.tool.ToolRuntimeContextFactory contextFactory,
        cn.lycode.tool.ToolExecutionInterceptor interceptor,
        SecurityRuntimePort securityRuntime,
        PermissionResponseGate responseGate,
        PermissionPromptPort promptPort,
        PermissionUpdateStore permissionUpdateStore,
        PermissionReviewer permissionReviewer
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
                permissionUpdateStore,
                permissionReviewer
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
            permissionUpdateStore,
            permissionReviewer
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
        ResourceSnapshot resources,
        McpClientManagerFactory managerFactory,
        McpClientManagerLifecycle managerLifecycle
    ) {
        if (resources == null || resources.mcpServers().isEmpty() || managerFactory == null) {
            return;
        }
        try {
            McpClientManager manager = managerFactory.create(cwd);
            managerLifecycle.track(manager);
            manager.connectAll(resources.mcpServers()).forEach(schema ->
                runtime.register(new McpToolAdapter(schema, manager::invoke))
            );
        } catch (RuntimeException exception) {
            // NOTE: MCP 注册失败不能阻断内置工具可用性。
        }
    }

    private ResourceSnapshot loadResources(ResourceRuntimePort resourceRuntime, Path cwd) {
        if (resourceRuntime == null) {
            return null;
        }
        try {
            return resourceRuntime.load(cwd);
        } catch (RuntimeException exception) {
            // NOTE: 资源加载失败不能阻断内置工具和通用 subagent。
            return null;
        }
    }

    private Optional<WebProviderRegistry> webProviderRegistry(
        LyCodeWebProperties properties,
        ObjectMapper objectMapper,
        Environment environment
    ) {
        if (properties == null || !properties.isEnabled()) {
            return Optional.empty();
        }
        JavaHttpWebClient client = new JavaHttpWebClient(
            new JavaHttpWebClient.HttpTransport() {
                private final java.net.http.HttpClient delegate = java.net.http.HttpClient.newHttpClient();

                @Override
                public java.net.http.HttpResponse<String> send(java.net.http.HttpRequest request)
                    throws java.io.IOException, InterruptedException {
                    return delegate.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                }
            },
            objectMapper,
            properties.getTimeout() == null ? Duration.ofSeconds(20) : properties.getTimeout()
        );
        Map<String, WebSearchProvider> searchProviders = new LinkedHashMap<>();
        if (providerEnabled(properties, "exa")) {
            searchProviders.put("exa", new ExaWebSearchProvider(client, objectMapper, endpoint(properties, "exa")));
        }
        apiKey(properties, environment, "tavily").ifPresent(apiKey -> {
            TavilyWebProvider tavily = new TavilyWebProvider(
                client,
                objectMapper,
                apiKey,
                endpoint(properties, "tavily")
            );
            searchProviders.put("tavily", tavily);
        });
        apiKey(properties, environment, "brave").ifPresent(apiKey ->
            searchProviders.put("brave", new BraveWebSearchProvider(client, apiKey, endpoint(properties, "brave")))
        );
        apiKey(properties, environment, "perplexity").ifPresent(apiKey ->
            searchProviders.put(
                "perplexity",
                new PerplexityWebSearchProvider(client, objectMapper, apiKey, endpoint(properties, "perplexity"))
            )
        );
        if (searchProviders.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new WebProviderRegistry(properties.getDefaultProvider(), searchProviders));
    }

    private boolean providerEnabled(LyCodeWebProperties properties, String providerName) {
        LyCodeWebProperties.ProviderProperties provider = properties.getProviders().get(providerName);
        return provider != null && provider.isEnabled();
    }

    private Optional<String> apiKey(LyCodeWebProperties properties, Environment environment, String providerName) {
        LyCodeWebProperties.ProviderProperties provider = properties.getProviders().get(providerName);
        if (provider == null) {
            return Optional.empty();
        }
        if (!provider.isEnabled()) {
            return Optional.empty();
        }
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            return Optional.of(provider.getApiKey().trim());
        }
        if (provider.getApiKeyEnv() != null && !provider.getApiKeyEnv().isBlank()) {
            String value = environment.getProperty(provider.getApiKeyEnv());
            if (value != null && !value.isBlank()) {
                return Optional.of(value.trim());
            }
        }
        return Optional.empty();
    }

    private String endpoint(LyCodeWebProperties properties, String providerName) {
        LyCodeWebProperties.ProviderProperties provider = properties.getProviders().get(providerName);
        if (provider == null || provider.getEndpoint() == null || provider.getEndpoint().isBlank()) {
            return null;
        }
        return provider.getEndpoint().trim();
    }
}
