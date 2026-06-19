package cn.lypi.contracts.runtime;

/**
 * 标识命令运行时沙盒策略的执行形态。
 */
public enum SandboxRuntimePolicyKind {
    /**
     * 使用 ly-pi 管理的本地沙盒执行。
     */
    MANAGED,

    /**
     * 不使用 ly-pi 沙盒，等价于 danger-full-access。
     */
    DISABLED,

    /**
     * 沙盒由外部环境负责，ly-pi 只保留标记和网络语义。
     */
    EXTERNAL
}
