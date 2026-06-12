package cn.lypi.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.boot.ai.LyPiAiProperties;
import cn.lypi.boot.runtime.LyPiRuntimeProperties;
import cn.lypi.boot.tool.LyPiToolProperties;
import cn.lypi.contracts.runtime.NetworkMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ApplicationExampleConfigTest {
    @Test
    void applicationExampleKeepsAllSettingsOptIn() throws IOException {
        Binder binder = binderForExample();

        assertThat(binder.bind("lypi.runtime", LyPiRuntimeProperties.class).isBound()).isFalse();
        assertThat(binder.bind("lypi.ai", LyPiAiProperties.class).isBound()).isFalse();
        assertThat(binder.bind("lypi.tool", LyPiToolProperties.class).isBound()).isFalse();
    }

    @Test
    void applicationExampleDoesNotAdvertiseCwdAsYamlKey() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);

        assertThat(example).doesNotContainPattern("(?m)^\\s*#?\\s*cwd\\s*:");
    }

    @Test
    void applicationExampleDocumentsSeparatedModeAndSandboxFailureSemantics() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);

        assertThat(example).contains("#     # 可选值：plan、execute。");
        assertThat(example).contains("#     # 可选值：default_execute、accept_edits、bypass。");
        assertThat(example).doesNotContain("#     # 可选值：plan、default_execute、accept_edits、dont_ask、bypass。");
        assertThat(example).doesNotContain("允许回退到宿主机执行器");
        assertThat(example).contains("不会自动回退到宿主机执行器");
    }

    @Test
    void overrideExtensionAndToolFragmentsBindToSupportedProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
            Map.entry("lypi.runtime.default-provider", "fixture"),
            Map.entry("lypi.runtime.default-model", "fixture-model"),
            Map.entry("lypi.runtime.thinking-level", "low"),
            Map.entry("lypi.ai.providers.openai.enabled", "true"),
            Map.entry("lypi.ai.providers.openai.api-style", "openai_compatible"),
            Map.entry("lypi.ai.providers.openai.request-style", "responses"),
            Map.entry("lypi.ai.providers.openai.fallback-request-style", "chat_completions"),
            Map.entry("lypi.ai.providers.openai.transport", "auto"),
            Map.entry("lypi.ai.providers.openai.base-url", "https://api.openai.test/v1"),
            Map.entry("lypi.ai.providers.openai.websocket-path", "/v1/responses"),
            Map.entry("lypi.ai.providers.openai.api-key", "${OPENAI_API_KEY:}"),
            Map.entry("lypi.ai.providers.openai.timeout", "30s"),
            Map.entry("lypi.ai.providers.openai.max-retries", "3"),
            Map.entry("lypi.ai.providers.openai.compat.safe-flag", "true"),
            Map.entry("lypi.ai.providers.openai.model-discovery.enabled", "false"),
            Map.entry("lypi.ai.providers.openai.model-discovery.paths[0]", "/models"),
            Map.entry("lypi.ai.providers.openai.model-discovery.paths[1]", "/model"),
            Map.entry("lypi.ai.providers.openai.models[0].model-id", "gpt-5-mini"),
            Map.entry("lypi.ai.providers.openai.models[0].context-window", "256000"),
            Map.entry("lypi.ai.providers.openai.models[0].max-output-tokens", "16384"),
            Map.entry("lypi.ai.providers.openai.models[0].supports-thinking", "true"),
            Map.entry("lypi.ai.providers.openai.models[0].supports-image-input", "true"),
            Map.entry("lypi.ai.providers.openai.models[0].input-token-cost", "0"),
            Map.entry("lypi.ai.providers.openai.models[0].output-token-cost", "0"),
            Map.entry("lypi.ai.providers.openai.models[0].currency", "USD"),
            Map.entry("lypi.ai.providers.openai.models[0].compat.vendor", "openai"),
            Map.entry("lypi.ai.providers.fixture.enabled", "true"),
            Map.entry("lypi.ai.providers.fixture.api-style", "openai_compatible"),
            Map.entry("lypi.ai.providers.fixture.request-style", "chat_completions"),
            Map.entry("lypi.ai.providers.fixture.base-url", "https://api.fixture.test/v1"),
            Map.entry("lypi.ai.providers.fixture.api-key", "${FIXTURE_API_KEY:}"),
            Map.entry("lypi.ai.providers.fixture.models[0].model-id", "fixture-model"),
            Map.entry("lypi.ai.providers.fixture.models[0].context-window", "64000"),
            Map.entry("lypi.ai.providers.fixture.models[0].max-output-tokens", "8192"),
            Map.entry("lypi.tool.sandbox.enabled", "true"),
            Map.entry("lypi.tool.sandbox.network-mode", "disabled"),
            Map.entry("lypi.tool.sandbox.fail-if-unavailable", "false"),
            Map.entry("lypi.tool.sandbox.auto-allow-bash-if-sandboxed", "false")
        )));

        LyPiRuntimeProperties runtime = binder.bind("lypi.runtime", LyPiRuntimeProperties.class).get();
        LyPiAiProperties ai = binder.bind("lypi.ai", LyPiAiProperties.class).get();
        LyPiToolProperties tool = binder.bind("lypi.tool", LyPiToolProperties.class).get();

        assertThat(runtime.getDefaultProvider()).isEqualTo("fixture");
        assertThat(runtime.getDefaultModel()).isEqualTo("fixture-model");
        assertThat(ai.getProviders()).containsOnlyKeys("openai", "fixture");
        assertThat(ai.getProviders().get("openai").isEnabled()).isTrue();
        assertThat(ai.getProviders().get("openai").getRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
        assertThat(ai.getProviders().get("openai").getFallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(ai.getProviders().get("openai").getTransport()).isEqualTo(TransportMode.AUTO);
        assertThat(ai.getProviders().get("openai").getBaseUrl().toString()).isEqualTo("https://api.openai.test/v1");
        assertThat(ai.getProviders().get("openai").getWebsocketPath()).isEqualTo("/v1/responses");
        assertThat(ai.getProviders().get("openai").getApiKey()).isEqualTo("${OPENAI_API_KEY:}");
        assertThat(ai.getProviders().get("openai").getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(ai.getProviders().get("openai").getMaxRetries()).isEqualTo(3);
        assertThat(ai.getProviders().get("openai").getCompat()).containsEntry("safe-flag", "true");
        assertThat(ai.getProviders().get("openai").getModelDiscovery().isEnabled()).isFalse();
        assertThat(ai.getProviders().get("openai").getModelDiscovery().getPaths()).containsExactly("/models", "/model");
        assertThat(ai.getProviders().get("openai").getModels())
            .extracting(LyPiAiProperties.ModelProperties::getModelId)
            .containsExactly("gpt-5-mini");
        assertThat(ai.getProviders().get("openai").getModels().getFirst())
            .satisfies(model -> {
                assertThat(model.getContextWindow()).isEqualTo(256000);
                assertThat(model.getMaxOutputTokens()).isEqualTo(16384);
                assertThat(model.isSupportsThinking()).isTrue();
                assertThat(model.isSupportsImageInput()).isTrue();
                assertThat(model.getCurrency()).isEqualTo("USD");
                assertThat(model.getCompat()).containsEntry("vendor", "openai");
            });
        assertThat(ai.getProviders().get("fixture").isEnabled()).isTrue();
        assertThat(ai.getProviders().get("fixture").getBaseUrl().toString()).isEqualTo("https://api.fixture.test/v1");
        assertThat(ai.getProviders().get("fixture").getModels()).singleElement().satisfies(model -> {
            assertThat(model.getModelId()).isEqualTo("fixture-model");
            assertThat(model.getContextWindow()).isEqualTo(64000);
            assertThat(model.getMaxOutputTokens()).isEqualTo(8192);
        });
        assertThat(tool.getSandbox().isEnabled()).isTrue();
        assertThat(tool.getSandbox().getNetworkMode()).isEqualTo(NetworkMode.DISABLED);
        assertThat(tool.getSandbox().isFailIfUnavailable()).isFalse();
        assertThat(tool.getSandbox().isAutoAllowBashIfSandboxed()).isFalse();
    }

    private Binder binderForExample() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
            .load("application-example", new ClassPathResource("application.yml.example"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }
}
