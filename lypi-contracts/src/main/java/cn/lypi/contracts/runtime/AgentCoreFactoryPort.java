package cn.lypi.contracts.runtime;

import java.nio.file.Path;

public interface AgentCoreFactoryPort {
    /**
     * 创建绑定到指定 cwd 和 session manager 的 AgentCore。
     *
     * NOTE: subagent child turn 必须使用 child session manager，避免写入父 session。
     */
    AgentCorePort create(Path cwd, SessionManagerPort sessionManager);
}
