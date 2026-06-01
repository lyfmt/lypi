package cn.lypi.contracts.runtime;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;

public interface Executor {
    /*
    * @status : 未完成
    * @summary : 返回执行器名称。
    *@description : 用于区分 Host、Docker、SSH 或其他沙盒执行器。
    *
    *
                              */
    String name();

    /*
    * @status : 未完成
    * @summary : 执行一次受策略约束的命令。
    *@description : 执行前必须由权限和沙盒策略完成决策，执行期间通过进度与中断接口协作。
    *
    *
                              */
    ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal);
}

