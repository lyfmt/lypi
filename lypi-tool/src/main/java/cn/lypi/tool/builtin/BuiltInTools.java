package cn.lypi.tool.builtin;

import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.tool.builtin.subagent.SpawnAgentTool;
import cn.lypi.tool.builtin.subagent.WaitAgentTool;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import cn.lypi.tool.web.WebFetchTool;
import cn.lypi.tool.web.GetSearchContentTool;
import cn.lypi.tool.web.WebProviderRegistry;
import cn.lypi.tool.web.WebResultStore;
import cn.lypi.tool.web.WebSearchTool;
import java.util.List;
import java.util.Objects;

public final class BuiltInTools {
    private BuiltInTools() {
    }

    /**
     * 创建默认内置工具集合。
     */
    public static List<Tool<?, ?>> createDefaultTools(Executor executor) {
        return createDefaultTools(executor, new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults()));
    }

    /**
     * 创建默认内置工具集合。
     */
    public static List<Tool<?, ?>> createDefaultTools(Executor executor, SandboxPolicyResolver sandboxPolicyResolver) {
        Objects.requireNonNull(executor, "executor must not be null");
        Objects.requireNonNull(sandboxPolicyResolver, "sandboxPolicyResolver must not be null");
        return List.of(
            new ReadTool(),
            new WriteTool(),
            new EditTool(),
            new RequestPermissionsTool(),
            new BashTool(executor, sandboxPolicyResolver),
            new GrepTool(executor),
            new GlobTool()
        );
    }

    /**
     * 注册默认内置工具集合。
     */
    public static void registerDefaults(ToolRuntimePort runtime, Executor executor) {
        registerDefaults(runtime, executor, new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults()));
    }

    /**
     * 注册默认内置工具集合。
     */
    public static void registerDefaults(ToolRuntimePort runtime, Executor executor, SandboxPolicyResolver sandboxPolicyResolver) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        for (Tool<?, ?> tool : createDefaultTools(executor, sandboxPolicyResolver)) {
            runtime.register(tool);
        }
    }

    /**
     * 注册 Web 工具集合。
     */
    public static void registerWebTools(ToolRuntimePort runtime, WebProviderRegistry providers) {
        registerWebTools(runtime, providers, 10);
    }

    /**
     * 注册 Web 工具集合。
     */
    public static void registerWebTools(ToolRuntimePort runtime, WebProviderRegistry providers, WebResultStore store) {
        registerWebTools(runtime, providers, store, 10);
    }

    /**
     * 注册 Web 工具集合。
     */
    public static void registerWebTools(ToolRuntimePort runtime, WebProviderRegistry providers, int maxResults) {
        registerWebTools(runtime, providers, WebResultStore.noop(), maxResults);
    }

    /**
     * 注册 Web 工具集合。
     */
    public static void registerWebTools(
        ToolRuntimePort runtime,
        WebProviderRegistry providers,
        WebResultStore store,
        int maxResults
    ) {
        WebResultStore resolvedStore = store == null ? WebResultStore.noop() : store;
        registerWebSearchTools(runtime, providers, resolvedStore, maxResults);
        registerWebFetchTool(runtime, resolvedStore);
        registerWebContentTools(runtime, resolvedStore);
    }

    /**
     * 注册本地 Web 内容取回工具。
     */
    public static void registerWebContentTools(ToolRuntimePort runtime, WebResultStore store) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        runtime.register(new GetSearchContentTool(store == null ? WebResultStore.noop() : store));
    }

    /**
     * 注册 Web 搜索工具集合。
     */
    public static void registerWebSearchTools(ToolRuntimePort runtime, WebProviderRegistry providers, int maxResults) {
        registerWebSearchTools(runtime, providers, WebResultStore.noop(), maxResults);
    }

    /**
     * 注册 Web 搜索工具集合。
     */
    public static void registerWebSearchTools(
        ToolRuntimePort runtime,
        WebProviderRegistry providers,
        WebResultStore store,
        int maxResults
    ) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(providers, "providers must not be null");
        if (!providers.searchProviderNames().isEmpty()) {
            runtime.register(new WebSearchTool(providers, store == null ? WebResultStore.noop() : store, maxResults, maxResults));
        }
    }

    /**
     * 注册本地 Web 抓取工具。
     */
    public static void registerWebFetchTool(ToolRuntimePort runtime) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        runtime.register(new WebFetchTool());
    }

    /**
     * 注册本地 Web 抓取工具。
     */
    public static void registerWebFetchTool(ToolRuntimePort runtime, WebResultStore store) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        runtime.register(new WebFetchTool(
            cn.lypi.tool.web.WebFetchTool.defaultFetcher(java.time.Duration.ofSeconds(20)),
            cn.lypi.tool.web.WebFetchTool.defaultCleaner(),
            store == null ? WebResultStore.noop() : store
        ));
    }

    /**
     * 注册本地 Web 抓取工具。
     */
    public static void registerWebFetchTool(ToolRuntimePort runtime, java.time.Duration timeout) {
        registerWebFetchTool(runtime, timeout, WebResultStore.noop());
    }

    /**
     * 注册本地 Web 抓取工具。
     */
    public static void registerWebFetchTool(ToolRuntimePort runtime, java.time.Duration timeout, WebResultStore store) {
        registerWebFetchTool(runtime, timeout, true, cn.lypi.tool.web.JinaReaderFetcher.DEFAULT_ENDPOINT, 200, store);
    }

    /**
     * 注册本地 Web 抓取工具。
     */
    public static void registerWebFetchTool(
        ToolRuntimePort runtime,
        java.time.Duration timeout,
        boolean jinaEnabled,
        String jinaEndpoint,
        int minBodyChars,
        WebResultStore store
    ) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        runtime.register(new WebFetchTool(
            cn.lypi.tool.web.WebFetchTool.defaultFetcher(timeout, jinaEnabled, jinaEndpoint, minBodyChars),
            cn.lypi.tool.web.WebFetchTool.defaultCleaner(),
            store == null ? WebResultStore.noop() : store
        ));
    }

    /**
     * 创建模型可见的 subagent 工具集合。
     */
    public static List<Tool<?, ?>> createSubagentTools(ToolRuntimePort runtime, AgentCenterPort agentCenter) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        Objects.requireNonNull(agentCenter, "agentCenter must not be null");
        return List.of(
            new SpawnAgentTool(runtime, agentCenter),
            new WaitAgentTool(agentCenter)
        );
    }

    /**
     * 注册模型可见的 subagent 工具集合。
     */
    public static void registerSubagentTools(ToolRuntimePort runtime, AgentCenterPort agentCenter) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        for (Tool<?, ?> tool : createSubagentTools(runtime, agentCenter)) {
            runtime.register(tool);
        }
    }
}
