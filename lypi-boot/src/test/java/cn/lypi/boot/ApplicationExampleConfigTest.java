package cn.lypi.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.ai.provider.RequestStyle;
import cn.lypi.ai.provider.TransportMode;
import cn.lypi.boot.ai.LyPiAiProperties;
import cn.lypi.boot.tool.LyPiToolProperties;
import cn.lypi.contracts.runtime.NetworkMode;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class ApplicationExampleConfigTest {
    @Test
    void applicationExampleBindsToSupportedProperties() throws IOException {
        Binder binder = binderForExample();

        LyPiAiProperties ai = binder.bind("lypi.ai", LyPiAiProperties.class).get();
        LyPiToolProperties tool = binder.bind("lypi.tool", LyPiToolProperties.class).get();

        assertThat(ai.getDefaultProvider()).isEqualTo("openai");
        assertThat(ai.getDefaultModel()).isEqualTo("gpt-5-mini");
        assertThat(ai.getProviders()).containsOnlyKeys("openai", "local");
        assertThat(ai.getProviders().get("openai").isEnabled()).isTrue();
        assertThat(ai.getProviders().get("openai").getRequestStyle()).isEqualTo(RequestStyle.RESPONSES);
        assertThat(ai.getProviders().get("openai").getFallbackRequestStyle()).isEqualTo(RequestStyle.CHAT_COMPLETIONS);
        assertThat(ai.getProviders().get("openai").getTransport()).isEqualTo(TransportMode.AUTO);
        assertThat(ai.getProviders().get("openai").getModels())
            .extracting(LyPiAiProperties.ModelProperties::getModelId)
            .containsExactly("gpt-5-mini", "gpt-5");
        assertThat(ai.getProviders().get("local").isEnabled()).isFalse();

        assertThat(tool.getSandbox().isEnabled()).isTrue();
        assertThat(tool.getSandbox().getNetworkMode()).isEqualTo(NetworkMode.DISABLED);
        assertThat(tool.getSandbox().isFailIfUnavailable()).isFalse();
    }

    private Binder binderForExample() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
            .load("application-example", new ClassPathResource("application.yml.example"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }
}
