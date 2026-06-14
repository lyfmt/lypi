package cn.lypi.contracts.runtime;

/**
 * 描述一次工具运行时调用的上层归属。
 *
 * NOTE: 该归属用于工具生命周期事件，不改变单个工具调用请求的模型来源。
 */
public record ToolRuntimeInvocation(
    String sessionId,
    String turnId,
    String parentEntryId
) {
    public ToolRuntimeInvocation(String sessionId, String turnId) {
        this(sessionId, turnId, null);
    }
}
