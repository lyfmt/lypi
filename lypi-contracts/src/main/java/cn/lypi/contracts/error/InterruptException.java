package cn.lypi.contracts.error;

public final class InterruptException extends LyPiException {
    public InterruptException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

