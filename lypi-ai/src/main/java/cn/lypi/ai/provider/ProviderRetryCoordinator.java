package cn.lypi.ai.provider;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.model.ProviderRetryNotice;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class ProviderRetryCoordinator {
    private static final Duration ABORT_POLL_INTERVAL = Duration.ofMillis(250);
    private final String provider;
    private final ProviderRetryPolicy policy;
    private final ProviderErrorClassifier classifier;
    private final ProviderBackoffStrategy backoffStrategy;
    private final Sleeper sleeper;

    public ProviderRetryCoordinator(
        String provider,
        ProviderRetryPolicy policy,
        ProviderErrorClassifier classifier,
        ProviderBackoffStrategy backoffStrategy,
        Sleeper sleeper
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.backoffStrategy = Objects.requireNonNull(backoffStrategy, "backoffStrategy");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
    }

    public static ProviderRetryCoordinator noSleep(String provider, ProviderRetryPolicy policy) {
        return new ProviderRetryCoordinator(
            provider,
            policy,
            new ProviderErrorClassifier(),
            new ProviderBackoffStrategy(),
            ignored -> {
            }
        );
    }

    public static ProviderRetryCoordinator defaultSleep(String provider, ProviderRetryPolicy policy) {
        return new ProviderRetryCoordinator(
            provider,
            policy,
            new ProviderErrorClassifier(),
            new ProviderBackoffStrategy(),
            ProviderRetryCoordinator::threadSleep
        );
    }

    public Optional<ProviderRetryNotice> planRetry(
        RuntimeException exception,
        AbortSignal signal,
        boolean outputStarted,
        int retryAttempt
    ) {
        ProviderErrorClassification classification = classifier.classify(exception, outputStarted);
        if (signal.aborted()
            || !classification.retryable()
            || outputStarted
            || retryAttempt > policy.maxRetries()) {
            return Optional.empty();
        }
        Duration delay = backoffStrategy.delay(retryAttempt, policy, classification);
        return Optional.of(new ProviderRetryNotice(
            provider,
            retryAttempt,
            policy.maxRetries(),
            delay,
            classification.reason(),
            classification.errorId(),
            exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
        ));
    }

    public void sleep(Duration delay) {
        sleeper.sleep(delay);
    }

    public void sleep(Duration delay, AbortSignal signal) {
        Duration remaining = delay;
        while (!remaining.isZero() && !remaining.isNegative() && !signal.aborted()) {
            Duration chunk = remaining.compareTo(ABORT_POLL_INTERVAL) > 0 ? ABORT_POLL_INTERVAL : remaining;
            sleeper.sleep(chunk);
            remaining = remaining.minus(chunk);
        }
    }

    private static void threadSleep(Duration delay) {
        try {
            Thread.sleep(delay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider retry backoff was interrupted.", exception);
        }
    }

    public <T> T execute(
        Supplier<T> operation,
        AbortSignal signal,
        boolean outputStarted,
        Consumer<ProviderRetryNotice> retryNoticeConsumer
    ) {
        for (int attempt = 1; ; attempt++) {
            try {
                return operation.get();
            } catch (RuntimeException exception) {
                Optional<ProviderRetryNotice> notice = planRetry(exception, signal, outputStarted, attempt);
                if (notice.isEmpty()) {
                    throw exception;
                }
                retryNoticeConsumer.accept(notice.get());
                sleep(notice.get().delay(), signal);
            }
        }
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration delay);
    }
}
