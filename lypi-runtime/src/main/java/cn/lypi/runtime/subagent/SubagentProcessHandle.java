package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import java.util.concurrent.CompletableFuture;

public interface SubagentProcessHandle {
    /**
     * 返回子进程最终输出。
     */
    CompletableFuture<HeadlessSubagentOutput> completion();

    /**
     * 请求中断子进程。
     */
    void interrupt();
}
