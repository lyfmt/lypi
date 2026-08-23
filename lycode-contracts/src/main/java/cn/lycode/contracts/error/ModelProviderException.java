package cn.lycode.contracts.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public final class ModelProviderException extends LyCodeException {
    @JsonCreator
    public ModelProviderException(
        @JsonProperty("errorId") String errorId,
        @JsonProperty("severity") ErrorSeverity severity,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("message") String message
    ) {
        super(errorId, severity, retryable, message);
    }
}
