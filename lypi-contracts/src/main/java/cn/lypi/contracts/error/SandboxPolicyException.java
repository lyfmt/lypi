package cn.lypi.contracts.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class SandboxPolicyException extends LyPiException {
    @JsonCreator
    public SandboxPolicyException(
        @JsonProperty("errorId") String errorId,
        @JsonProperty("severity") ErrorSeverity severity,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("message") String message
    ) {
        super(errorId, severity, retryable, message);
    }
}
