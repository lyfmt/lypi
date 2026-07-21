package cn.lypi.contracts.subagent;

public record SubagentWaitRequest(String parentSessionId, long timeoutMillis) {
    public SubagentWaitRequest {
        timeoutMillis = Math.max(0, timeoutMillis);
    }
}
