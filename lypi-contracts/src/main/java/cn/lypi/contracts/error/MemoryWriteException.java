package cn.lypi.contracts.error;

public final class MemoryWriteException extends LyPiException {
    public MemoryWriteException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

