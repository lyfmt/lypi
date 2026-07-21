package cn.lypi.contracts.runtime;

import cn.lypi.contracts.agent.SteeringMessageSource;
import cn.lypi.contracts.common.AbortSignal;

/**
 * 描述一次工具运行时调用的上层归属。
 *
 * NOTE: 该归属用于工具生命周期事件，不改变单个工具调用请求的模型来源。
 */
public record ToolRuntimeInvocation(
    String sessionId,
    String turnId,
    String parentEntryId,
    AbortSignal abortSignal,
    SteeringMessageSource steeringMessages
) {
    public ToolRuntimeInvocation(String sessionId, String turnId) {
        this(sessionId, turnId, null);
    }

    public ToolRuntimeInvocation(String sessionId, String turnId, String parentEntryId) {
        this(sessionId, turnId, parentEntryId, AbortSignal.none(), SteeringMessageSource.none());
    }

    public ToolRuntimeInvocation {
        abortSignal = abortSignal == null ? AbortSignal.none() : abortSignal;
        steeringMessages = steeringMessages == null ? SteeringMessageSource.none() : steeringMessages;
    }
}
