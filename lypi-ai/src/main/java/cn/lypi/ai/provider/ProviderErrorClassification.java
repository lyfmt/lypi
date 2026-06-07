package cn.lypi.ai.provider;

import java.time.Duration;
import java.util.Optional;

public record ProviderErrorClassification(
    String errorId,
    String reason,
    boolean retryable,
    boolean fallbackAllowed,
    Optional<Duration> retryAfter
) {
    public ProviderErrorClassification {
        retryAfter = retryAfter == null ? Optional.empty() : retryAfter;
    }
}
