package cn.lypi.contracts.error;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonIgnoreProperties({"cause", "stackTrace", "suppressed", "localizedMessage"})
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ModelProviderException.class, name = "model_provider"),
    @JsonSubTypes.Type(value = ToolValidationException.class, name = "tool_validation"),
    @JsonSubTypes.Type(value = ToolPermissionException.class, name = "tool_permission"),
    @JsonSubTypes.Type(value = ToolExecutionException.class, name = "tool_execution"),
    @JsonSubTypes.Type(value = SandboxPolicyException.class, name = "sandbox_policy"),
    @JsonSubTypes.Type(value = ContextOverflowException.class, name = "context_overflow"),
    @JsonSubTypes.Type(value = CompactionException.class, name = "compaction"),
    @JsonSubTypes.Type(value = MemoryWriteException.class, name = "memory_write"),
    @JsonSubTypes.Type(value = InterruptException.class, name = "interrupt")
})
public class LyPiException extends RuntimeException {
    private final String errorId;
    private final ErrorSeverity severity;
    private final boolean retryable;

    @JsonCreator
    public LyPiException(
        @JsonProperty("errorId") String errorId,
        @JsonProperty("severity") ErrorSeverity severity,
        @JsonProperty("retryable") boolean retryable,
        @JsonProperty("message") String message
    ) {
        super(message);
        this.errorId = errorId;
        this.severity = severity;
        this.retryable = retryable;
    }

    @JsonProperty("errorId")
    public String errorId() {
        return errorId;
    }

    @JsonProperty("severity")
    public ErrorSeverity severity() {
        return severity;
    }

    @JsonProperty("retryable")
    public boolean retryable() {
        return retryable;
    }
}
