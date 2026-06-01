package cn.lypi.contracts.error;

public final class ToolValidationException extends LyPiException {
    public ToolValidationException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

