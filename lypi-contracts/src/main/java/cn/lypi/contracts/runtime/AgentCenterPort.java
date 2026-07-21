package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;

public interface AgentCenterPort {
    /**
     * 启动一个 child session subagent。
     *
     * NOTE: 该方法只返回启动结果，最终结果必须进入 mailbox，不返回延迟 tool_result。
     */
    SubagentSpawnResult spawn(SubagentSpawnRequest request);

    /**
     * 等待当前 parent session 任意 subagent completion。
     */
    SubagentWaitResult waitFor(SubagentWaitRequest request);

    /**
     * 中断运行中的 subagent。
     */
    MailboxCommandResult interrupt(String agentId);
}
