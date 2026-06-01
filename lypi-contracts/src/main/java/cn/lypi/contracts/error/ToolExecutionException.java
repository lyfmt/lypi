package cn.lypi.contracts.error;

public final class ToolExecutionException extends LyPiException {
    public ToolExecutionException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

