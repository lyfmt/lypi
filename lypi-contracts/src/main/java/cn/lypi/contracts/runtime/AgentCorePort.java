package cn.lypi.contracts.runtime;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;

public interface AgentCorePort {
    /**
     * 执行一轮用户输入。
     *
     * NOTE: AgentCore 负责 turn 生命周期编排，所有持久化必须经 SessionEnginePort，所有工具执行必须经 ToolRuntimePort。
     */
    TurnState execute(TurnRequest request);
}
