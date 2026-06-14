package cn.lypi.ai.provider;

import java.time.Duration;

public final class ProviderBackoffStrategy {
    public Duration delay(int attempt, ProviderRetryPolicy policy, ProviderErrorClassification classification) {
        if (classification.retryAfter().isPresent()) {
            return classification.retryAfter().get();
        }
        double multiplier = Math.pow(policy.backoffMultiplier(), Math.max(0, attempt - 1));
        long delayMillis = Math.round(policy.initialDelay().toMillis() * multiplier);
        return Duration.ofMillis(Math.min(delayMillis, policy.maxDelay().toMillis()));
    }
}
