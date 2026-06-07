package cn.lypi.ai.provider;

import java.time.Duration;

public record ProviderRetryPolicy(
    int maxRetries,
    Duration initialDelay,
    double backoffMultiplier,
    Duration maxDelay
) {
    public static ProviderRetryPolicy defaults(int maxRetries) {
        return new ProviderRetryPolicy(maxRetries, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32));
    }

    public ProviderRetryPolicy {
        maxRetries = Math.max(0, maxRetries);
        initialDelay = initialDelay == null ? Duration.ofMillis(500) : initialDelay;
        maxDelay = maxDelay == null ? Duration.ofSeconds(32) : maxDelay;
        if (backoffMultiplier <= 0) {
            backoffMultiplier = 2.0;
        }
    }
}
