package cn.lypi.contracts.model;

import java.time.Duration;

public record ProviderRetryNotice(
    String provider,
    int attempt,
    int maxRetries,
    Duration delay,
    String reason,
    String retryableErrorId,
    String message
) implements AssistantStreamEvent {
    public ProviderRetryNotice {
        delay = delay == null ? Duration.ZERO : delay;
    }
}
