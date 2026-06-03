package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelDescriptor;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RemoteModelDescriptorSourceTest {
    @Test
    void mapsDiscoveredModelIdsToDescriptors() {
        RecordingDiscoveryClient client = new RecordingDiscoveryClient(List.of("remote-a", "remote-b"));
        RemoteModelDescriptorSource source = new RemoteModelDescriptorSource(
            true,
            "test-provider",
            URI.create("https://provider.example/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            "secret-key",
            List.of("/models", "/model"),
            Duration.ofSeconds(3),
            client,
            defaults()
        );

        assertThat(source.list())
            .extracting(ModelDescriptor::provider, ModelDescriptor::modelId, ModelDescriptor::baseUrl, ModelDescriptor::apiStyle)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("test-provider", "remote-a", URI.create("https://provider.example/v1"), ApiStyle.OPENAI_COMPATIBLE),
                org.assertj.core.groups.Tuple.tuple("test-provider", "remote-b", URI.create("https://provider.example/v1"), ApiStyle.OPENAI_COMPATIBLE)
            );
        assertThat(client.baseUrl).isEqualTo(URI.create("https://provider.example/v1"));
        assertThat(client.apiKey).isEqualTo("secret-key");
        assertThat(client.paths).containsExactly("/models", "/model");
        assertThat(client.timeout).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void descriptorsInheritDefaultsWithoutSecretCompat() {
        RemoteModelDescriptorSource source = new RemoteModelDescriptorSource(
            true,
            "test-provider",
            URI.create("https://provider.example/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            "secret-key",
            List.of("/models"),
            Duration.ofSeconds(3),
            new RecordingDiscoveryClient(List.of("remote-a")),
            new RemoteModelDescriptorSource.DescriptorDefaults(
                200_000,
                32_000,
                true,
                true,
                new CostProfile(BigDecimal.ONE, BigDecimal.TEN, "USD"),
                Map.of(
                    "apiKey", "secret",
                    "client_secret", "secret",
                    "secret_key", "secret",
                    "x-api-key", "secret",
                    "refresh_token", "secret",
                    "safe", "value"
                )
            )
        );

        ModelDescriptor descriptor = source.list().getFirst();

        assertThat(descriptor.contextWindow()).isEqualTo(200_000);
        assertThat(descriptor.maxOutputTokens()).isEqualTo(32_000);
        assertThat(descriptor.supportsThinking()).isTrue();
        assertThat(descriptor.supportsImageInput()).isTrue();
        assertThat(descriptor.costProfile()).isEqualTo(new CostProfile(BigDecimal.ONE, BigDecimal.TEN, "USD"));
        assertThat(descriptor.compat()).containsEntry("safe", "value");
        assertThat(descriptor.compat()).doesNotContainKeys("apiKey", "client_secret", "secret_key", "x-api-key", "refresh_token");
    }

    @Test
    void returnsEmptyWhenDiscoveryDisabled() {
        RecordingDiscoveryClient client = new RecordingDiscoveryClient(List.of("remote-a"));
        RemoteModelDescriptorSource source = new RemoteModelDescriptorSource(
            false,
            "test-provider",
            URI.create("https://provider.example/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            "secret-key",
            List.of("/models"),
            Duration.ofSeconds(3),
            client,
            defaults()
        );

        assertThat(source.list()).isEmpty();
        assertThat(client.called).isFalse();
    }

    private static RemoteModelDescriptorSource.DescriptorDefaults defaults() {
        return new RemoteModelDescriptorSource.DescriptorDefaults(
            128_000,
            16_384,
            false,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
    }

    private static final class RecordingDiscoveryClient extends RemoteModelDiscoveryClient {
        private final List<String> modelIds;
        private boolean called;
        private URI baseUrl;
        private String apiKey;
        private List<String> paths;
        private Duration timeout;

        private RecordingDiscoveryClient(List<String> modelIds) {
            this.modelIds = modelIds;
        }

        @Override
        public List<String> discover(URI baseUrl, String apiKey, List<String> paths, Duration timeout) {
            this.called = true;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
            this.paths = paths;
            this.timeout = timeout;
            return modelIds;
        }
    }
}
