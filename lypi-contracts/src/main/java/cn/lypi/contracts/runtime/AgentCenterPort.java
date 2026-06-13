package cn.lypi.contracts.runtime;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.MailboxCommandResult;
import cn.lypi.contracts.subagent.SubagentContinueRequest;
import cn.lypi.contracts.subagent.SubagentContinueResult;
import cn.lypi.contracts.subagent.SubagentSpawnRequest;
import cn.lypi.contracts.subagent.SubagentSpawnResult;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.util.Optional;

public interface AgentCenterPort {
    /**
     * 启动一个 child session subagent。
     *
     * NOTE: 该方法只返回启动结果，最终结果必须进入 mailbox，不返回延迟 tool_result。
     */
    SubagentSpawnResult spawn(SubagentSpawnRequest request);

    /**
     * 向已有 child session 发送一轮新输入。
     */
    SubagentContinueResult continueRun(SubagentContinueRequest request);

    /**
     * 等待指定 subagent run 完成。
     */
    SubagentWaitResult waitFor(SubagentWaitRequest request);

    /**
     * 中断运行中的 subagent。
     */
    MailboxCommandResult interrupt(String agentId);

    /**
     * 读取 child session 或 result ref 中的最终结果。
     */
    Optional<HeadlessSubagentOutput> readResult(String childSessionId);
}
