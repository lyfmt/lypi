package cn.lypi.ai.provider;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ProviderErrorClassifier {
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("retry-after[:= ]+(\\d+)", Pattern.CASE_INSENSITIVE);

    public ProviderErrorClassification classify(RuntimeException error, boolean visibleOutputStarted) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String normalized = message.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "aborted", "abort")) {
            return noRetry("provider.aborted", "aborted");
        }
        if (containsAny(normalized, "api key", "401", "403", "unauthorized", "forbidden")) {
            return noRetry("provider.authentication", "authentication");
        }
        if (containsAny(normalized, "400", "invalid request", "validation")) {
            return noRetry("provider.invalid_request", "invalid_request");
        }
        if (containsAny(normalized, "429", "rate limit", "rate_limit")) {
            return retry("provider.rate_limit", "rate_limit", retryAfter(message), false);
        }
        boolean fallbackCandidate = containsAny(
            normalized,
            "unsupported",
            "404",
            "405",
            "handshake failed",
            "without assistantdone"
        );
        if (fallbackCandidate) {
            return new ProviderErrorClassification(
                "provider.fallback_candidate",
                "fallback_candidate",
                true,
                !visibleOutputStarted,
                retryAfter(message)
            );
        }
        if (containsAny(normalized, "500", "502", "503", "504", "timeout", "timed out", "connection reset", "econnreset", "epipe")) {
            return retry("provider.transient", "transient", retryAfter(message), false);
        }
        return noRetry("provider.failed", "failed");
    }

    private ProviderErrorClassification retry(
        String errorId,
        String reason,
        Optional<Duration> retryAfter,
        boolean fallbackAllowed
    ) {
        return new ProviderErrorClassification(errorId, reason, true, fallbackAllowed, retryAfter);
    }

    private ProviderErrorClassification noRetry(String errorId, String reason) {
        return new ProviderErrorClassification(errorId, reason, false, false, Optional.empty());
    }

    private Optional<Duration> retryAfter(String message) {
        Matcher matcher = RETRY_AFTER_PATTERN.matcher(message);
        if (!matcher.find()) {
            return Optional.empty();
        }
        return Optional.of(Duration.ofSeconds(Long.parseLong(matcher.group(1))));
    }

    private boolean containsAny(String message, String... needles) {
        for (String needle : needles) {
            if (message.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
