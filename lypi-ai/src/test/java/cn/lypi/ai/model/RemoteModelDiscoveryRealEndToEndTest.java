package cn.lypi.ai.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class RemoteModelDiscoveryRealEndToEndTest {
    @Test
    @Timeout(30)
    void discoversModelsFromConfiguredRealProvider() {
        assumeTrue(Boolean.getBoolean("lypi.provider.e2e"), "Enable with -Dlypi.provider.e2e=true");
        RealProviderSettings settings = RealProviderSettings.fromSystemProperties();

        List<String> modelIds = new RemoteModelDiscoveryClient().discover(
            settings.baseUrl(),
            settings.apiKey(),
            List.of("/models", "/model"),
            Duration.ofSeconds(20)
        );

        assertThat(modelIds)
            .as("real provider discovery from %s should return models; provider may not support /models or /model",
                settings.baseUrl())
            .isNotEmpty();
        assertThat(modelIds)
            .as("real provider discovery should include configured model %s in %s", settings.model(), modelIds)
            .contains(settings.model());
    }

    private record RealProviderSettings(
        URI baseUrl,
        String apiKey,
        String model
    ) {
        private static RealProviderSettings fromSystemProperties() {
            return new RealProviderSettings(
                requiredUri("lypi.provider.e2e.base-url"),
                required("lypi.provider.e2e.api-key"),
                required("lypi.provider.e2e.model")
            );
        }

        private static URI requiredUri(String key) {
            return URI.create(required(key));
        }

        private static String required(String key) {
            String value = System.getProperty(key);
            assumeTrue(value != null && !value.isBlank(), "Missing required system property: " + key);
            return value;
        }
    }
}
