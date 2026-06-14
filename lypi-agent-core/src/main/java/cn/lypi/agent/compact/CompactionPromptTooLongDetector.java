package cn.lypi.agent.compact;

import cn.lypi.contracts.error.ContextOverflowException;
import cn.lypi.contracts.error.LyPiException;
import cn.lypi.contracts.model.AssistantError;
import java.util.Locale;

final class CompactionPromptTooLongDetector {
    private CompactionPromptTooLongDetector() {
    }

    static boolean isPromptTooLong(RuntimeException exception) {
        if (exception instanceof ContextOverflowException) {
            return true;
        }
        if (exception instanceof LyPiException lyPiException && containsPromptTooLong(lyPiException.errorId())) {
            return true;
        }
        return containsPromptTooLong(message(exception));
    }

    static boolean isPromptTooLong(AssistantError error) {
        return error != null
            && (containsPromptTooLong(error.errorId()) || containsPromptTooLong(error.message()));
    }

    static boolean isPromptTooLong(String text) {
        return containsPromptTooLong(text);
    }

    private static boolean containsPromptTooLong(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("prompt too long")
            || normalized.contains("context length")
            || normalized.contains("context_length")
            || normalized.contains("context_length_exceeded")
            || normalized.contains("maximum context")
            || normalized.contains("too many tokens")
            || normalized.contains("token limit");
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
