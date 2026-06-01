package cn.lypi.contracts.error;

public final class SandboxPolicyException extends LyPiException {
    public SandboxPolicyException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

