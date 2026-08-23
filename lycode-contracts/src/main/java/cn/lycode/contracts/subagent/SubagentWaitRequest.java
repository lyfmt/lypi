package cn.lycode.contracts.subagent;

import cn.lycode.contracts.agent.SteeringMessageSource;
import cn.lycode.contracts.common.AbortSignal;
import com.fasterxml.jackson.annotation.JsonIgnore;

public record SubagentWaitRequest(
    String parentSessionId,
    long timeoutMillis,
    @JsonIgnore AbortSignal abortSignal,
    @JsonIgnore SteeringMessageSource steeringMessages
) {
    public SubagentWaitRequest(String parentSessionId, long timeoutMillis) {
        this(parentSessionId, timeoutMillis, AbortSignal.none(), SteeringMessageSource.none());
    }

    public SubagentWaitRequest {
        timeoutMillis = Math.max(0, timeoutMillis);
        abortSignal = abortSignal == null ? AbortSignal.none() : abortSignal;
        steeringMessages = steeringMessages == null ? SteeringMessageSource.none() : steeringMessages;
    }
}
