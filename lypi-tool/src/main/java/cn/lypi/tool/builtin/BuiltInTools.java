package cn.lypi.tool.builtin;

import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.tool.builtin.subagent.AcceptMailboxMessageTool;
import cn.lypi.tool.builtin.subagent.InterruptAgentTool;
import cn.lypi.tool.builtin.subagent.ReadAgentResultTool;
import cn.lypi.tool.builtin.subagent.ReadMailboxTool;
import cn.lypi.tool.builtin.subagent.SpawnAgentTool;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
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
            new BashTool(executor, sandboxPolicyResolver),
            new GrepTool(),
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
     * 创建 subagent mailbox 工具集合。
     */
    public static List<Tool<?, ?>> createSubagentTools(AgentCenterPort agentCenter, MailboxPort mailbox) {
        Objects.requireNonNull(agentCenter, "agentCenter must not be null");
        Objects.requireNonNull(mailbox, "mailbox must not be null");
        return List.of(
            new SpawnAgentTool(agentCenter),
            new InterruptAgentTool(agentCenter),
            new ReadAgentResultTool(agentCenter),
            new ReadMailboxTool(mailbox),
            new AcceptMailboxMessageTool(mailbox)
        );
    }

    /**
     * 注册 subagent mailbox 工具集合。
     */
    public static void registerSubagentTools(ToolRuntimePort runtime, AgentCenterPort agentCenter, MailboxPort mailbox) {
        Objects.requireNonNull(runtime, "runtime must not be null");
        for (Tool<?, ?> tool : createSubagentTools(agentCenter, mailbox)) {
            runtime.register(tool);
        }
    }
}
