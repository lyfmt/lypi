package cn.lycode.session;

import cn.lycode.contracts.error.ErrorSeverity;
import cn.lycode.contracts.error.LyCodeException;

/**
 * Session 引擎异常。
 */
public final class SessionEngineException extends LyCodeException {
    public SessionEngineException(String message) {
        super("SESSION_ENGINE_ERROR", ErrorSeverity.ERROR, false, message);
    }

    public SessionEngineException(String message, Throwable cause) {
        super("SESSION_ENGINE_ERROR", ErrorSeverity.ERROR, false, message);
        initCause(cause);
    }
}
