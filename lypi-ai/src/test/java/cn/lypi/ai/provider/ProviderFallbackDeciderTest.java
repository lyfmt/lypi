package cn.lypi.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderFallbackDeciderTest {
    @Test
    void fallsBackForUnsupportedEndpointBeforeAnyDelta() {
        ProviderFallbackDecider decider = new ProviderFallbackDecider();

        assertThat(decider.shouldFallback(new IllegalStateException("Provider HTTP 404: endpoint unsupported"), false))
            .isTrue();
        assertThat(decider.shouldFallback(new IllegalStateException("Provider HTTP 400: unsupported request style"), false))
            .isTrue();
        assertThat(decider.shouldFallback(new IllegalStateException("WebSocket handshake failed"), false))
            .isTrue();
        assertThat(decider.shouldFallback(new IllegalStateException("Provider WebSocket handshake failed."), false))
            .isTrue();
        assertThat(decider.shouldFallback(new IllegalStateException("Provider stream completed without AssistantDone."), false))
            .isTrue();
    }

    @Test
    void doesNotFallbackAfterOutputOrForAuthRateLimitAndAbort() {
        ProviderFallbackDecider decider = new ProviderFallbackDecider();

        assertThat(decider.shouldFallback(new IllegalStateException("Provider HTTP 404: endpoint unsupported"), true))
            .isFalse();
        assertThat(decider.shouldFallback(new IllegalStateException("Provider HTTP 401: unauthorized"), false))
            .isFalse();
        assertThat(decider.shouldFallback(new IllegalStateException("Provider HTTP 429: rate limit"), false))
            .isFalse();
        assertThat(decider.shouldFallback(new IllegalStateException("aborted"), false))
            .isFalse();
    }
}
