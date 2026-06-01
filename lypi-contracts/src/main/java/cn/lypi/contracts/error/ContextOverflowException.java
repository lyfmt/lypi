package cn.lypi.contracts.error;

public final class ContextOverflowException extends LyPiException {
    public ContextOverflowException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

