package cn.lypi.contracts.common;

public interface AbortSignal {
    /**
     * 判断当前操作是否已收到中断信号。
     *
     * 供模型流、工具执行、外部进程等长耗时操作轮询使用。
     */
    boolean aborted();
}

