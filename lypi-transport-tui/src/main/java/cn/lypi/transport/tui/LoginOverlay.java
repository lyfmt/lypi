package cn.lypi.transport.tui;

import java.util.List;
import java.util.Optional;

/** Temporary three-step input state for provider login credentials. */
final class LoginOverlay {
    private enum Step {
        CHANNEL_NAME,
        BASE_URL,
        AUTH_KEY
    }

    private final StringBuilder channelName = new StringBuilder();
    private final StringBuilder baseUrl = new StringBuilder();
    private final StringBuilder authKey = new StringBuilder();
    private Step step = Step.CHANNEL_NAME;
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
        if (step == Step.CHANNEL_NAME) {
            if (channelName.toString().isBlank()) {
                return Optional.empty();
            }
            step = Step.BASE_URL;
            return Optional.empty();
        }
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
        return Optional.of(new Submission(channelName.toString(), baseUrl.toString(), authKey.toString()));
    }

    void clear() {
        clear(channelName);
        clear(baseUrl);
        clear(authKey);
        step = Step.CHANNEL_NAME;
        open = false;
    }

    List<String> lines() {
        if (!open) {
            return List.of();
        }
        return switch (step) {
            case CHANNEL_NAME -> List.of("Channel name: " + channelName);
            case BASE_URL -> List.of(
                "Channel name: " + channelName,
                "Base URL: " + baseUrl
            );
            case AUTH_KEY -> List.of(
                "Channel name: " + channelName,
                "Base URL: " + baseUrl,
                "Auth key: " + "*".repeat(authKey.length())
            );
        };
    }

    private StringBuilder currentInput() {
        return switch (step) {
            case CHANNEL_NAME -> channelName;
            case BASE_URL -> baseUrl;
            case AUTH_KEY -> authKey;
        };
    }

    private static void clear(StringBuilder value) {
        for (int index = 0; index < value.length(); index++) {
            value.setCharAt(index, '\0');
        }
        value.setLength(0);
    }

    record Submission(String channelName, String baseUrl, String authKey) {
        @Override
        public String toString() {
            return "Submission[channelName=" + channelName + ", baseUrl=" + baseUrl + ", authKey=<redacted>]";
        }
    }
}
