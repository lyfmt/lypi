package cn.lypi.contracts.error;

public final class CompactionException extends LyPiException {
    public CompactionException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

