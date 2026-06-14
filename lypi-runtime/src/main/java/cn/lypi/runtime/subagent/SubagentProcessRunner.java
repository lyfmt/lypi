package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.HeadlessSubagentInput;

public interface SubagentProcessRunner {
    /**
     * 启动 headless subagent 子进程。
     */
    SubagentProcessHandle start(HeadlessSubagentInput input);
}
