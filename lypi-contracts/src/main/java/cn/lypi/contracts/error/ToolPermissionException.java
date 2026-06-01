package cn.lypi.contracts.error;

public final class ToolPermissionException extends LyPiException {
    public ToolPermissionException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

