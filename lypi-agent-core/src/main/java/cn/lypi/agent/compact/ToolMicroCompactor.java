package cn.lypi.agent.compact;

/**
 * 对工具结果执行请求前微压缩投影。
 *
 * NOTE: 实现不得写 session、不得发布用户可感知事件，也不得修改原始 ContextSnapshot。
 */
public interface ToolMicroCompactor {
    /**
     * 返回用于本次模型请求的上下文视图。
     *
     * 原始会话历史必须保持不变。
     */
    ToolMicroCompactResult compact(ToolMicroCompactRequest request);

    /**
     * 清空进程内微压缩状态。
     *
     * NOTE: session compact、manual compact 或外部状态重置后调用，避免复用旧 cache epoch。
     */
    default void reset() {
    }
}
