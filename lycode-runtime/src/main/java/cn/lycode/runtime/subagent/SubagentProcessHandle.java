package cn.lycode.runtime.subagent;

import cn.lycode.contracts.subagent.HeadlessSubagentOutput;
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
