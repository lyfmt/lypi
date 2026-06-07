package cn.lypi.contracts.runtime;

import cn.lypi.contracts.agent.PermissionResumeRequest;
import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;

public interface AgentCorePort {
    /**
     * 执行一轮用户输入。
     *
     * NOTE: AgentCore 负责 turn 生命周期编排，所有持久化必须经 SessionManagerPort，所有工具执行必须经 ToolRuntimePort。
     */
    TurnState execute(TurnRequest request);

    /**
     * 恢复一次等待权限的 turn。
     *
     * NOTE: 权限响应只描述用户决策；agent-core 必须从 session 中恢复原 pending 工具调用。
     */
    TurnState resumePermission(PermissionResumeRequest request);
}
