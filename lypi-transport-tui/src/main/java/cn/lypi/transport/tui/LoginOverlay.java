package cn.lypi.transport.tui;

import java.util.List;
import java.util.Optional;

/** Temporary two-step input state for provider login credentials. */
final class LoginOverlay {
    private enum Step {
        BASE_URL,
        AUTH_KEY
    }

    private final StringBuilder baseUrl = new StringBuilder();
    private final StringBuilder authKey = new StringBuilder();
    private Step step = Step.BASE_URL;
    private boolean open;

    void open() {
        clear();
        open = true;
    }

    void append(String text) {
        if (text != null) {
            open = true;
            currentInput().append(text);
        }
    }

    void backspace() {
        StringBuilder input = currentInput();
        if (!input.isEmpty()) {
            input.deleteCharAt(input.length() - 1);
        }
    }

    Optional<Submission> accept() {
        if (step == Step.BASE_URL) {
            if (baseUrl.toString().isBlank()) {
                return Optional.empty();
            }
            step = Step.AUTH_KEY;
            return Optional.empty();
        }
        if (authKey.toString().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Submission(baseUrl.toString(), authKey.toString()));
    }

    void clear() {
        clear(baseUrl);
        clear(authKey);
        step = Step.BASE_URL;
        open = false;
    }

    List<String> lines() {
        if (!open) {
            return List.of();
        }
        if (step == Step.BASE_URL) {
            return List.of("Base URL: " + baseUrl);
        }
        return List.of(
            "Base URL: " + baseUrl,
            "Auth key: " + "*".repeat(authKey.length())
        );
    }

    private StringBuilder currentInput() {
        return step == Step.BASE_URL ? baseUrl : authKey;
    }

    private static void clear(StringBuilder value) {
        for (int index = 0; index < value.length(); index++) {
            value.setCharAt(index, '\0');
        }
        value.setLength(0);
    }

    record Submission(String baseUrl, String authKey) {
        @Override
        public String toString() {
            return "Submission[baseUrl=" + baseUrl + ", authKey=<redacted>]";
        }
    }
}
