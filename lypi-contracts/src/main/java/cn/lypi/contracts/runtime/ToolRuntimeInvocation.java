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
    SteeringMessageSource steeringMessages,
    java.nio.file.Path cwd
) {
    private static final AbortSignal INHERIT_ABORT_SIGNAL = () -> false;
    private static final SteeringMessageSource INHERIT_STEERING_MESSAGES = java.util.Optional::empty;

    public ToolRuntimeInvocation(String sessionId, String turnId) {
        this(sessionId, turnId, null);
    }

    public ToolRuntimeInvocation(String sessionId, String turnId, String parentEntryId) {
        this(sessionId, turnId, parentEntryId, AbortSignal.none(), SteeringMessageSource.none());
    }

    public ToolRuntimeInvocation(
        String sessionId,
        String turnId,
        String parentEntryId,
        AbortSignal abortSignal,
        SteeringMessageSource steeringMessages
    ) {
        this(sessionId, turnId, parentEntryId, abortSignal, steeringMessages, null);
    }

    public ToolRuntimeInvocation {
        abortSignal = abortSignal == null ? AbortSignal.none() : abortSignal;
        steeringMessages = steeringMessages == null ? SteeringMessageSource.none() : steeringMessages;
    }

    /**
     * Creates an invocation that overrides only cwd and inherits runtime-configured activity signals.
     */
    public static ToolRuntimeInvocation cwdOnly(java.nio.file.Path cwd) {
        return new ToolRuntimeInvocation(
            null,
            null,
            null,
            INHERIT_ABORT_SIGNAL,
            INHERIT_STEERING_MESSAGES,
            cwd
        );
    }

    public boolean inheritsRuntimeSignals() {
        return abortSignal == INHERIT_ABORT_SIGNAL && steeringMessages == INHERIT_STEERING_MESSAGES;
    }

    /**
     * Dynamic working directory for this tool invocation. The runtime workspace root remains stable.
     */
    public ToolRuntimeInvocation withCwd(java.nio.file.Path cwdOverride) {
        return new ToolRuntimeInvocation(sessionId, turnId, parentEntryId, abortSignal, steeringMessages, cwdOverride);
    }
}
