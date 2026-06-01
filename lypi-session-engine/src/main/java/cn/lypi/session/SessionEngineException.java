package cn.lypi.session;

import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.LyPiException;

public final class SessionEngineException extends LyPiException {
    public SessionEngineException(String message) {
        super("SESSION_ENGINE_ERROR", ErrorSeverity.ERROR, false, message);
    }

    public SessionEngineException(String message, Throwable cause) {
        super("SESSION_ENGINE_ERROR", ErrorSeverity.ERROR, false, message);
        initCause(cause);
    }
}
