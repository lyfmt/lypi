package cn.lypi.contracts.tool;

/**
 * 表示一次工具调用的最终执行状态。
 */
public enum ToolExecutionStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    TIMED_OUT
}
