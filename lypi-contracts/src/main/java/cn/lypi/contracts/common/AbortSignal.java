package cn.lypi.contracts.common;

public interface AbortSignal {
    AbortSignal NONE = () -> false;

    /**
     * 判断当前操作是否已收到中断信号。
     *
     * 供模型流、工具执行、外部进程等长耗时操作轮询使用。
     */
    boolean aborted();

    default SignalSubscription subscribe(Runnable listener) {
        if (aborted()) {
            listener.run();
        }
        return SignalSubscription.none();
    }

    static AbortSignal none() {
        return NONE;
    }
}
