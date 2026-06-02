package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;

public interface Executor {
    /**
     * 返回执行器名称。
     *
     * 用于区分 Host、Docker、SSH 或其他沙盒执行器。
     */
    String name();

    /**
     * 执行一次受策略约束的命令。
     *
     * NOTE: 执行前必须由权限和沙盒策略完成决策，执行期间通过进度与中断接口协作。
     */
    ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal);
}
