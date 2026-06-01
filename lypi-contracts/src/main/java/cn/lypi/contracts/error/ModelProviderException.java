package cn.lypi.contracts.error;

public final class ModelProviderException extends LyPiException {
    public ModelProviderException(String errorId, ErrorSeverity severity, boolean retryable, String message) {
        super(errorId, severity, retryable, message);
    }
}

