package cn.lypi.contracts.error;

public class LyPiException extends RuntimeException {
    private final String errorId;
    private final ErrorSeverity severity;
    private final boolean retryable;

    public LyPiException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(message);
        this.errorId = errorId;
        this.severity = severity;
        this.retryable = retryable;
    }

    public String errorId() {
        return errorId;
    }

    public ErrorSeverity severity() {
        return severity;
    }

    public boolean retryable() {
        return retryable;
    }
}

