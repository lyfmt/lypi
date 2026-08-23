package cn.lycode.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.ai.provider.RequestStyle;
import cn.lycode.ai.provider.TransportMode;
import cn.lycode.boot.ai.LyCodeAiProperties;
import cn.lycode.boot.runtime.LyCodeRuntimeProperties;
import cn.lycode.boot.tool.LyCodePermissionsProperties;
import cn.lycode.boot.tool.LyCodeToolProperties;
import cn.lycode.contracts.model.ApiStyle;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.FileSystemAccessMode;
import cn.lycode.contracts.security.FileSystemPath;
import cn.lycode.contracts.security.FileSystemPolicyKind;
import cn.lycode.contracts.security.NetworkPolicyMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;

class ApplicationExampleConfigTest {
    @Test
    void applicationExampleKeepsAllSettingsOptIn() throws IOException {
        Binder binder = binderForExample();

        assertThat(binder.bind("lycode.runtime", LyCodeRuntimeProperties.class).isBound()).isFalse();
        assertThat(binder.bind("lycode.ai", LyCodeAiProperties.class).isBound()).isFalse();
        assertThat(binder.bind("lycode.tool", LyCodeToolProperties.class).isBound()).isFalse();
    }

    @Test
    void applicationExamplePointsToUserRootConfiguration() throws IOException {
        String example = new ClassPathResource("application.yml.example")
            .getContentAsString(StandardCharsets.UTF_8);

        assertThat(example)
            .contains("~/.ly-code/application.yml")
            .contains("运行目录中的 application.yml")
            .doesNotContain("复制本文件为 application.yml");
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
        assertThat(example).contains("#     # 可选值：ask、auto、bypass。");
        assertThat(example).contains("#     permission-mode: ask");
        assertThat(example).doesNotContain("default_execute").doesNotContain("accept_edits");
        assertThat(example).contains("最终复核路由由 runtime.permission-mode 决定");
        assertThat(example).doesNotContain("审批策略决定运行时是否可以询问用户");
        assertThat(example).doesNotContain("允许回退到宿主机执行器");
        assertThat(example).contains("不会自动回退到宿主机执行器");
    }

    @Test
    void applicationExampleDocumentsHeadlessSubagentCommandConfiguration() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);

        assertThat(example).contains("#   subagent:");
        assertThat(example).contains("#     command:");
        assertThat(example).contains("headless-subagent");
    }

    @Test
    void applicationExampleDocumentsAnthropicProviderConfiguration() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);
        String anthropicBlock = example.substring(
            example.indexOf("#       anthropic:"),
            example.indexOf("#   tool:")
        );

        assertThat(anthropicBlock).contains("#       anthropic:");
        assertThat(anthropicBlock).contains("#         api-style: anthropic");
        assertThat(anthropicBlock).contains("#         base-url: https://api.anthropic.com/v1");
        assertThat(anthropicBlock).contains("#         api-key: \"${ANTHROPIC_API_KEY:}\"");
        assertThat(anthropicBlock).contains("#         anthropic-version: 2023-06-01");
        assertThat(anthropicBlock).contains("#             model-id: claude-sonnet-4-5");
        assertThat(anthropicBlock).contains("Anthropic extended thinking 需要保留并回放 signature");
        assertThat(anthropicBlock).contains("runtime.thinking-level: off");
        assertThat(anthropicBlock).contains("#             supports-thinking: false");
        assertThat(anthropicBlock).doesNotContain("#             supports-thinking: true");
    }

    @Test
    void applicationExampleKeepsOpenAiThinkingSupportSeparateFromAnthropicLimitation() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);
        String openAiBlock = example.substring(
            example.indexOf("#       openai:"),
            example.indexOf("#       fixture:")
        );

        assertThat(openAiBlock).contains("#             supports-thinking: true");
        assertThat(openAiBlock).doesNotContain("Anthropic extended thinking");
    }

    @Test
    void applicationExampleDocumentsDiscoveredChatCompletionsProvider() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);
        String fixtureBlock = example.substring(
            example.indexOf("#       fixture:"),
            example.indexOf("#       anthropic:")
        );

        assertThat(fixtureBlock).contains("#         request-style: chat_completions");
        assertThat(fixtureBlock).contains("#         fallback-request-style: chat_completions");
        assertThat(fixtureBlock).contains("#         transport: sse");
        assertThat(fixtureBlock).contains("#           enabled: true");
        assertThat(fixtureBlock).contains("#             - /models");
        assertThat(fixtureBlock).contains("#             - /model");
        assertThat(fixtureBlock).contains("作为远端同名模型的完整描述覆盖");
        assertThat(fixtureBlock).contains("远端未返回的静态 model-id 仍不会进入模型目录");
        assertThat(fixtureBlock)
            .contains("#           requires-reasoning-content-on-assistant-messages: true");
    }

    @Test
    void applicationExampleParsesDiscoveryDefaultsAndChatCompatibility() throws IOException {
        StandardEnvironment environment = environmentForAiExample();

        assertThat(environment.getProperty(
            "lycode.ai.model-discovery.defaults.context-window",
            Integer.class
        )).isEqualTo(256000);
        assertThat(environment.getProperty(
            "lycode.ai.model-discovery.defaults.max-output-tokens",
            Integer.class
        )).isEqualTo(8192);
        assertThat(environment.getProperty(
            "lycode.ai.model-discovery.defaults.supports-thinking",
            Boolean.class
        )).isTrue();
        assertThat(environment.getProperty(
            "lycode.ai.model-discovery.defaults.supports-image-input",
            Boolean.class
        )).isTrue();
        assertThat(environment.getProperty("lycode.ai.providers.fixture.request-style"))
            .isEqualTo("chat_completions");
        assertThat(environment.getProperty("lycode.ai.providers.fixture.fallback-request-style"))
            .isEqualTo("chat_completions");
        assertThat(environment.getProperty("lycode.ai.providers.fixture.transport"))
            .isEqualTo("sse");
        assertThat(environment.getProperty(
            "lycode.ai.providers.fixture.compat.requires-reasoning-content-on-assistant-messages",
            Boolean.class
        )).isTrue();
    }

    @Test
    void applicationExampleDocumentsPermissionsAtLyCodeTopLevel() throws IOException {
        String example = new ClassPathResource("application.yml.example").getContentAsString(StandardCharsets.UTF_8);

        assertThat(example).contains("#   runtime:");
        assertThat(example).contains("#   permissions:");
        assertThat(example).contains("#   subagent:");
        assertThat(example.indexOf("#   runtime:")).isLessThan(example.indexOf("#   permissions:"));
        assertThat(example.indexOf("#   permissions:")).isLessThan(example.indexOf("#   subagent:"));
    }

    @Test
    void overrideExtensionAndToolFragmentsBindToSupportedProperties() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.ofEntries(
            Map.entry("lycode.runtime.default-provider", "fixture"),
            Map.entry("lycode.runtime.default-model", "fixture-model"),
            Map.entry("lycode.runtime.thinking-level", "low"),
            Map.entry("lycode.ai.providers.openai.enabled", "true"),
            Map.entry("lycode.ai.providers.openai.api-style", "openai_compatible"),
            Map.entry("lycode.ai.providers.openai.request-style", "responses"),
            Map.entry("lycode.ai.providers.openai.fallback-request-style", "chat_completions"),
            Map.entry("lycode.ai.providers.openai.transport", "auto"),
            Map.entry("lycode.ai.providers.openai.base-url", "https://api.openai.test/v1"),
            Map.entry("lycode.ai.providers.openai.websocket-path", "/v1/responses"),
            Map.entry("lycode.ai.providers.openai.api-key", "${OPENAI_API_KEY:}"),
            Map.entry("lycode.ai.providers.openai.timeout", "30s"),
            Map.entry("lycode.ai.providers.openai.max-retries", "3"),
            Map.entry("lycode.ai.providers.openai.compat.safe-flag", "true"),
            Map.entry("lycode.ai.providers.openai.model-discovery.enabled", "false"),
            Map.entry("lycode.ai.providers.openai.model-discovery.paths[0]", "/models"),
            Map.entry("lycode.ai.providers.openai.model-discovery.paths[1]", "/model"),
            Map.entry("lycode.ai.providers.openai.models[0].model-id", "gpt-5-mini"),
            Map.entry("lycode.ai.providers.openai.models[0].context-window", "256000"),
            Map.entry("lycode.ai.providers.openai.models[0].max-output-tokens", "16384"),
            Map.entry("lycode.ai.providers.openai.models[0].supports-thinking", "true"),
            Map.entry("lycode.ai.providers.openai.models[0].supports-image-input", "true"),
            Map.entry("lycode.ai.providers.openai.models[0].input-token-cost", "0"),
            Map.entry("lycode.ai.providers.openai.models[0].output-token-cost", "0"),
            Map.entry("lycode.ai.providers.openai.models[0].currency", "USD"),
            Map.entry("lycode.ai.providers.openai.models[0].compat.vendor", "openai"),
            Map.entry("lycode.ai.providers.fixture.enabled", "true"),
            Map.entry("lycode.ai.providers.fixture.api-style", "openai_compatible"),
            Map.entry("lycode.ai.providers.fixture.request-style", "chat_completions"),
            Map.entry("lycode.ai.providers.fixture.base-url", "https://api.fixture.test/v1"),
            Map.entry("lycode.ai.providers.fixture.api-key", "${FIXTURE_API_KEY:}"),
            Map.entry("lycode.ai.providers.fixture.models[0].model-id", "fixture-model"),
            Map.entry("lycode.ai.providers.fixture.models[0].context-window", "64000"),
            Map.entry("lycode.ai.providers.fixture.models[0].max-output-tokens", "8192"),
            Map.entry("lycode.ai.providers.anthropic.enabled", "true"),
            Map.entry("lycode.ai.providers.anthropic.api-style", "anthropic"),
            Map.entry("lycode.ai.providers.anthropic.base-url", "https://api.anthropic.test/v1"),
            Map.entry("lycode.ai.providers.anthropic.api-key", "${ANTHROPIC_API_KEY:}"),
            Map.entry("lycode.ai.providers.anthropic.anthropic-version", "2023-06-01"),
            Map.entry("lycode.ai.providers.anthropic.timeout", "45s"),
            Map.entry("lycode.ai.providers.anthropic.max-retries", "2"),
            Map.entry("lycode.ai.providers.anthropic.models[0].model-id", "claude-sonnet-4-5"),
            Map.entry("lycode.ai.providers.anthropic.models[0].context-window", "200000"),
            Map.entry("lycode.ai.providers.anthropic.models[0].max-output-tokens", "64000"),
            Map.entry("lycode.ai.providers.anthropic.models[0].supports-thinking", "false"),
            Map.entry("lycode.tool.sandbox.enabled", "true"),
            Map.entry("lycode.tool.sandbox.network-mode", "disabled"),
            Map.entry("lycode.tool.sandbox.fail-if-unavailable", "false"),
            Map.entry("lycode.tool.sandbox.auto-allow-bash-if-sandboxed", "false"),
            Map.entry("lycode.permissions.default-permissions", ":workspace"),
            Map.entry("lycode.permissions.approval-policy.mode", "granular"),
            Map.entry("lycode.permissions.approval-policy.granular.rules", "never"),
            Map.entry("lycode.permissions.profiles.dev.description", "Developer workspace"),
            Map.entry("lycode.permissions.profiles.dev.extends-profile", ":workspace"),
            Map.entry("lycode.permissions.profiles.dev.workspace-roots[0]", "/absolute/path/to/extra-workspace"),
            Map.entry("lycode.permissions.profiles.dev.file-system.kind", "restricted"),
            Map.entry("lycode.permissions.profiles.dev.file-system.entries[0].path.kind", "special"),
            Map.entry("lycode.permissions.profiles.dev.file-system.entries[0].path.value", ":root"),
            Map.entry("lycode.permissions.profiles.dev.file-system.entries[0].access", "read"),
            Map.entry("lycode.permissions.profiles.dev.network.mode", "enabled")
        )));

        LyCodeRuntimeProperties runtime = binder.bind("lycode.runtime", LyCodeRuntimeProperties.class).get();
        LyCodeAiProperties ai = binder.bind("lycode.ai", LyCodeAiProperties.class).get();
        LyCodeToolProperties tool = binder.bind("lycode.tool", LyCodeToolProperties.class).get();
        LyCodePermissionsProperties permissions = binder.bind("lycode.permissions", LyCodePermissionsProperties.class).get();

        assertThat(runtime.getDefaultProvider()).isEqualTo("fixture");
        assertThat(runtime.getDefaultModel()).isEqualTo("fixture-model");
        assertThat(ai.getProviders()).containsOnlyKeys("openai", "fixture", "anthropic");
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
            .extracting(LyCodeAiProperties.ModelProperties::getModelId)
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
        assertThat(ai.getProviders().get("anthropic").isEnabled()).isTrue();
        assertThat(ai.getProviders().get("anthropic").getApiStyle()).isEqualTo(ApiStyle.ANTHROPIC);
        assertThat(ai.getProviders().get("anthropic").getBaseUrl().toString()).isEqualTo("https://api.anthropic.test/v1");
        assertThat(ai.getProviders().get("anthropic").getApiKey()).isEqualTo("${ANTHROPIC_API_KEY:}");
        assertThat(ai.getProviders().get("anthropic").getAnthropicVersion()).isEqualTo("2023-06-01");
        assertThat(ai.getProviders().get("anthropic").getTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(ai.getProviders().get("anthropic").getMaxRetries()).isEqualTo(2);
        assertThat(ai.getProviders().get("anthropic").getModels()).singleElement().satisfies(model -> {
            assertThat(model.getModelId()).isEqualTo("claude-sonnet-4-5");
            assertThat(model.getContextWindow()).isEqualTo(200000);
            assertThat(model.getMaxOutputTokens()).isEqualTo(64000);
            assertThat(model.isSupportsThinking()).isFalse();
        });
        assertThat(tool.getSandbox().isEnabled()).isTrue();
        assertThat(tool.getSandbox().getNetworkMode()).isEqualTo(NetworkMode.DISABLED);
        assertThat(tool.getSandbox().isFailIfUnavailable()).isFalse();
        assertThat(tool.getSandbox().isAutoAllowBashIfSandboxed()).isFalse();
        assertThat(permissions.getDefaultPermissions()).isEqualTo(":workspace");
        assertThat(permissions.getApprovalPolicy().toApprovalPolicy().mode()).isEqualTo(ApprovalMode.GRANULAR);
        assertThat(permissions.getApprovalPolicy().toApprovalPolicy().granularApprovalPolicy().orElseThrow().rules())
            .isEqualTo(ApprovalMode.NEVER);
        assertThat(permissions.getProfiles()).containsKey("dev");
        assertThat(permissions.getProfiles().get("dev").toConfig().fileSystem().orElseThrow().kind())
            .isEqualTo(FileSystemPolicyKind.RESTRICTED);
        assertThat(permissions.getProfiles().get("dev").toConfig().fileSystem().orElseThrow().entries().getFirst().path().kind())
            .isEqualTo(FileSystemPath.Kind.SPECIAL);
        assertThat(permissions.getProfiles().get("dev").toConfig().fileSystem().orElseThrow().entries().getFirst().access())
            .isEqualTo(FileSystemAccessMode.READ);
        assertThat(permissions.getProfiles().get("dev").toConfig().network().orElseThrow().mode())
            .isEqualTo(NetworkPolicyMode.ENABLED);
    }

    private Binder binderForExample() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
            .load("application-example", new ClassPathResource("application.yml.example"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }

    private StandardEnvironment environmentForAiExample() throws IOException {
        String example = new ClassPathResource("application.yml.example")
            .getContentAsString(StandardCharsets.UTF_8);
        String aiBlock = example.substring(
            example.indexOf("#   ai:"),
            example.indexOf("#   tool:")
        );
        String yaml = "lycode:\n" + aiBlock.lines()
            .map(line -> line.equals("#") ? "" : line.startsWith("# ") ? line.substring(2) : line)
            .collect(Collectors.joining("\n"));
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
            .load(
                "application-example-ai",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8))
            )
            .forEach(environment.getPropertySources()::addLast);
        return environment;
    }
}
