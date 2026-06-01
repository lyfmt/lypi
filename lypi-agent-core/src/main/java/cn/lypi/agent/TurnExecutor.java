package cn.lypi.agent;

import cn.lypi.contracts.agent.TurnRequest;
import cn.lypi.contracts.agent.TurnState;
import cn.lypi.contracts.runtime.AgentCorePort;

public interface TurnExecutor extends AgentCorePort {
    /*
    * @status : 未完成
    * @summary : 执行一轮用户输入。
    *@description : 负责 turn 生命周期编排，所有持久化必须经 SessionEngine，所有工具执行必须经 ToolOrchestrator。
    *
    *
                              */
    TurnState execute(TurnRequest request);
}

