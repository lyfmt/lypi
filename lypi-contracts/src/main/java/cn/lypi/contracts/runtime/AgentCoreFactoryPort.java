package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.nio.file.Path;

public interface AgentCoreFactoryPort {
    /**
     * 创建绑定到指定 cwd 和 session manager 的 AgentCore。
     *
     * NOTE: subagent child turn 必须使用 child session manager，避免写入父 session。
     */
    AgentCorePort create(Path cwd, SessionManagerPort sessionManager);

    /**
     * 创建绑定到指定 cwd、session manager 和 subagent 工具策略的 AgentCore。
     *
     * NOTE: 默认实现保持向后兼容；支持工具隔离的实现应使用 toolPolicy 创建过滤后的 ToolRuntime。
     */
    default AgentCorePort create(Path cwd, SessionManagerPort sessionManager, SubagentToolPolicy toolPolicy) {
        return create(cwd, sessionManager);
    }
}
