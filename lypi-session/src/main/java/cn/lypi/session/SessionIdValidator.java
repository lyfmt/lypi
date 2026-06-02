package cn.lypi.session;

import java.util.regex.Pattern;

final class SessionIdValidator {
    private static final Pattern SAFE_SESSION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,127}");

    private SessionIdValidator() {
    }

    static void validate(String sessionId) {
        if (sessionId == null || !SAFE_SESSION_ID.matcher(sessionId).matches()) {
            throw new SessionEngineException("Invalid session id: " + sessionId);
        }
    }
}
