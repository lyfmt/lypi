package cn.lypi.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.model.ProviderRetryNotice;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProviderRetryCoordinatorTest {
    @Test
    void retriesRateLimitFailureAndEmitsNoticeBeforeSuccessfulAttempt() {
        List<ProviderRetryNotice> notices = new ArrayList<>();
        List<Duration> sleeps = new ArrayList<>();
        ProviderRetryCoordinator coordinator = new ProviderRetryCoordinator(
            "openai",
            new ProviderRetryPolicy(2, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32)),
            new ProviderErrorClassifier(),
            new ProviderBackoffStrategy(),
            sleeps::add
        );
        int[] attempts = {0};

        String result = coordinator.execute(() -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                throw new IllegalStateException("Provider HTTP 429: rate limit");
            }
            return "ok";
        }, () -> false, false, notices::add);

        assertThat(result).isEqualTo("ok");
        assertThat(attempts[0]).isEqualTo(2);
        assertThat(totalSleep(sleeps)).isEqualTo(Duration.ofMillis(500));
        assertThat(notices).containsExactly(new ProviderRetryNotice(
            "openai",
            1,
            2,
            Duration.ofMillis(500),
            "rate_limit",
            "provider.rate_limit",
            "Provider HTTP 429: rate limit"
        ));
    }

    @Test
    void doesNotRetryAuthenticationFailure() {
        ProviderRetryCoordinator coordinator = ProviderRetryCoordinator.noSleep(
            "openai",
            new ProviderRetryPolicy(2, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32))
        );

        assertThatThrownBy(() -> coordinator.execute(
            () -> {
                throw new IllegalStateException("Provider HTTP 401: unauthorized");
            },
            () -> false,
            false,
            ignored -> {
            }
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("401");
    }

    @Test
    void retriesConnectionResetFailure() {
        List<ProviderRetryNotice> notices = new ArrayList<>();
        ProviderRetryCoordinator coordinator = ProviderRetryCoordinator.noSleep(
            "openai",
            new ProviderRetryPolicy(1, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32))
        );
        int[] attempts = {0};

        String result = coordinator.execute(() -> {
            attempts[0]++;
            if (attempts[0] == 1) {
                throw new IllegalStateException("Connection reset");
            }
            return "ok";
        }, () -> false, false, notices::add);

        assertThat(result).isEqualTo("ok");
        assertThat(notices)
            .singleElement()
            .extracting(ProviderRetryNotice::retryableErrorId)
            .isEqualTo("provider.transient");
    }

    @Test
    void usesRetryAfterWhenPresent() {
        ProviderBackoffStrategy strategy = new ProviderBackoffStrategy();
        ProviderErrorClassification classification = new ProviderErrorClassification(
            "provider.rate_limit",
            "rate_limit",
            true,
            true,
            Optional.of(Duration.ofSeconds(2))
        );

        Duration delay = strategy.delay(1, new ProviderRetryPolicy(
            3,
            Duration.ofMillis(500),
            2.0,
            Duration.ofSeconds(32)
        ), classification);

        assertThat(delay).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void abortSignalPreventsRetry() {
        ProviderRetryCoordinator coordinator = ProviderRetryCoordinator.noSleep(
            "openai",
            new ProviderRetryPolicy(2, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32))
        );
        AbortSignal signal = () -> true;

        assertThatThrownBy(() -> coordinator.execute(
            () -> {
                throw new IllegalStateException("Provider HTTP 429: rate limit");
            },
            signal,
            false,
            ignored -> {
            }
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("429");
    }

    @Test
    void sleepStopsEarlyWhenSignalIsAborted() {
        MutableAbortSignal signal = new MutableAbortSignal();
        List<Duration> sleeps = new ArrayList<>();
        ProviderRetryCoordinator coordinator = new ProviderRetryCoordinator(
            "openai",
            new ProviderRetryPolicy(2, Duration.ofMillis(500), 2.0, Duration.ofSeconds(32)),
            new ProviderErrorClassifier(),
            new ProviderBackoffStrategy(),
            delay -> {
                sleeps.add(delay);
                signal.abort();
            }
        );

        coordinator.sleep(Duration.ofMillis(1_500), signal);

        assertThat(sleeps).containsExactly(Duration.ofMillis(250));
    }

    private Duration totalSleep(List<Duration> sleeps) {
        return sleeps.stream().reduce(Duration.ZERO, Duration::plus);
    }

    private static final class MutableAbortSignal implements AbortSignal {
        private boolean aborted;

        @Override
        public boolean aborted() {
            return aborted;
        }

        private void abort() {
            aborted = true;
        }
    }
}
