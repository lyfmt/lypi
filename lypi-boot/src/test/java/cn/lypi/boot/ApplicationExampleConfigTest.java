package cn.lypi.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.boot.ai.LyPiAiProperties;
import cn.lypi.boot.runtime.LyPiRuntimeProperties;
import cn.lypi.boot.tool.LyPiToolProperties;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ApplicationExampleConfigTest {
    @Test
    void applicationExampleBindsToSupportedProperties() throws IOException {
        Binder binder = binderForExample();

        LyPiRuntimeProperties runtime = binder.bind("lypi.runtime", LyPiRuntimeProperties.class).get();
        LyPiAiProperties ai = binder.bind("lypi.ai", LyPiAiProperties.class).get();
        LyPiToolProperties tool = binder.bind("lypi.tool", LyPiToolProperties.class).get();

        assertThat(runtime.getSessionId()).isEqualTo("default");
        assertThat(runtime.getDefaultProvider()).isEqualTo("openai");
        assertThat(runtime.getDefaultModel()).isEqualTo("gpt-5-mini");
        assertThat(runtime.getThinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(runtime.getAgentMode()).isEqualTo(AgentMode.EXECUTE);
        assertThat(runtime.getPermissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
        assertThat(runtime.getTransport()).isEqualTo("headless");
        assertThat(runtime.getInitialPrompt()).isEmpty();

        assertThat(ai.getDefaultProvider()).isEqualTo("openai");
        assertThat(ai.getDefaultModel()).isEqualTo("gpt-5-mini");
        assertThat(ai.getCompactionSummary().isEnabled()).isFalse();
        assertThat(ai.getCompactionSummary().getFallbackPolicy())
            .isEqualTo(CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC);
        assertThat(ai.getProviders()).containsOnlyKeys("openai", "local");
        assertThat(ai.getProviders().get("openai").isEnabled()).isTrue();
        assertThat(ai.getProviders().get("openai").getApiStyle().name()).isEqualTo("OPENAI_COMPATIBLE");
        assertThat(ai.getProviders().get("openai").getRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
        assertThat(ai.getProviders().get("openai").getFallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(ai.getProviders().get("openai").getTransport()).isEqualTo(TransportMode.AUTO);
        assertThat(ai.getProviders().get("openai").getBaseUrl().toString()).isEqualTo("https://api.openai.com/v1");
        assertThat(ai.getProviders().get("openai").getWebsocketPath()).isEqualTo("/v1/responses");
        assertThat(ai.getProviders().get("openai").getApiKey()).isEmpty();
        assertThat(ai.getProviders().get("openai").getTimeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(ai.getProviders().get("openai").getMaxRetries()).isEqualTo(3);
        assertThat(ai.getProviders().get("openai").getCompat()).containsEntry("safe-flag", true);
        assertThat(ai.getProviders().get("openai").getModelDiscovery().isEnabled()).isFalse();
        assertThat(ai.getProviders().get("openai").getModelDiscovery().getPaths()).containsExactly("/models", "/model");
        assertThat(ai.getProviders().get("openai").getModels())
            .extracting(LyPiAiProperties.ModelProperties::getModelId)
            .containsExactly("gpt-5-mini", "gpt-5");
        assertThat(ai.getProviders().get("openai").getModels().getFirst())
            .satisfies(model -> {
                assertThat(model.getContextWindow()).isEqualTo(256000);
                assertThat(model.getMaxOutputTokens()).isEqualTo(16384);
                assertThat(model.isSupportsThinking()).isTrue();
                assertThat(model.isSupportsImageInput()).isTrue();
                assertThat(model.getCurrency()).isEqualTo("USD");
                assertThat(model.getCompat()).containsEntry("vendor", "openai");
            });
        assertThat(ai.getProviders().get("local").isEnabled()).isFalse();
        assertThat(ai.getProviders().get("local").getModels()).singleElement().satisfies(model -> {
            assertThat(model.getModelId()).isEqualTo("local-model");
            assertThat(model.getContextWindow()).isEqualTo(32768);
            assertThat(model.getMaxOutputTokens()).isEqualTo(4096);
            assertThat(model.isSupportsThinking()).isFalse();
            assertThat(model.isSupportsImageInput()).isFalse();
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
