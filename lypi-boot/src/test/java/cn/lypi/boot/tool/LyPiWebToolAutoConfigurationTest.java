package cn.lypi.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

final class LyPiWebToolAutoConfigurationTest {
    @Test
    void webToolsAreDisabledByDefault() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isEmpty();
                assertThat(runtime.resolve("web_fetch")).isEmpty();
            });
    }

    @Test
    void registersExaSearchAndLocalFetchWhenEnabledWithoutProviderKey() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues("lypi.web.enabled=true")
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isPresent();
                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();
            });
    }

    @Test
    void getSearchContentUsesRuntimeCwdCache(@TempDir Path runtimeCwd) throws Exception {
        Path storeFile = runtimeCwd.resolve(".ly-pi").resolve("web-results.jsonl");
        Files.createDirectories(storeFile.getParent());
        Files.writeString(
            storeFile,
            """
            {"sessionId":"session","messageId":"message","responseId":"web_20260623_000001","sourceTool":"web_fetch","query":null,"url":"https://example.com/doc","items":[{"url":"https://example.com/doc","title":"Example","snippet":null,"content":"Cached body","format":"markdown","truncated":false,"source":"local"}],"createdAt":"2026-06-23T00:00:00Z"}
            """,
            StandardCharsets.UTF_8
        );

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.runtime.cwd=" + runtimeCwd
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                List<ToolResult<?>> results = runtime.execute(
                    List.of(new ToolUseRequest(
                        "toolu_content",
                        "get_search_content",
                        Map.of("responseId", "web_20260623_000001"),
                        "message"
                    )),
                    contextSnapshot(),
                    new ToolRuntimeInvocation("session", "turn")
                );

                assertThat(results).hasSize(1);
                assertThat(results.getFirst().isError()).isFalse();
                assertThat(results.getFirst().output().toString()).contains("Cached body");
            });
    }

    @Test
    void cacheCanBeDisabledAndContentToolReportsThatClearly() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.cache.enabled=false"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();

                List<ToolResult<?>> results = runtime.execute(
                    List.of(new ToolUseRequest(
                        "toolu_content",
                        "get_search_content",
                        Map.of("responseId", "web_20260623_000001"),
                        "message"
                    )),
                    contextSnapshot(),
                    new ToolRuntimeInvocation("session", "turn")
                );

                assertThat(results).hasSize(1);
                assertThat(results.getFirst().isError()).isTrue();
                assertThat(results.getFirst().output().toString()).contains("Web 结果缓存未启用");
            });
    }

    @Test
    void runtimeCwdCreatesIsolatedWebCacheStores(@TempDir Path tempDir) throws Exception {
        Path firstCwd = tempDir.resolve("first");
        Path secondCwd = tempDir.resolve("second");
        writeCachedFetch(firstCwd, "First cwd body");
        writeCachedFetch(secondCwd, "Second cwd body");

        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues("lypi.web.enabled=true")
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimeFactoryPort factory = context.getBean(ToolRuntimeFactoryPort.class);

                ToolResult<?> first = readCachedBody(factory.create(firstCwd));
                ToolResult<?> second = readCachedBody(factory.create(secondCwd));

                assertThat(first.isError()).isFalse();
                assertThat(first.output().toString())
                    .contains("First cwd body")
                    .doesNotContain("Second cwd body");
                assertThat(second.isError()).isFalse();
                assertThat(second.output().toString())
                    .contains("Second cwd body")
                    .doesNotContain("First cwd body");
            });
    }

    @Test
    void webFetchConfigurationBindsJinaAndFallbackOptions() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.fetch.fallback.enabled=false",
                "lypi.web.fetch.fallback.min-body-chars=321",
                "lypi.web.fetch.jina.enabled=false",
                "lypi.web.fetch.jina.endpoint=https://reader.example/http://"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                LyPiWebProperties properties = context.getBean(LyPiWebProperties.class);

                assertThat(properties.getFetch().getFallback().isEnabled()).isFalse();
                assertThat(properties.getFetch().getFallback().getMinBodyChars()).isEqualTo(321);
                assertThat(properties.getFetch().getJina().isEnabled()).isFalse();
                assertThat(properties.getFetch().getJina().getEndpoint()).isEqualTo("https://reader.example/http://");
                assertThat(context.getBean(ToolRuntimePort.class).resolve("web_fetch")).isPresent();
            });
    }

    @Test
    void registersTavilySearchAndLocalFetchWhenApiKeyIsConfigured() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.providers.tavily.api-key=test-key"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isPresent();
                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();
            });
    }

    @Test
    void providerDisableDoesNotDisableLocalFetch() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.providers.tavily.enabled=false",
                "lypi.web.providers.tavily.api-key=test-key",
                "lypi.web.providers.exa.enabled=false"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isEmpty();
                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();
            });
    }

    @Test
    void exaCanBeDisabledWithoutCommercialProviderKeys() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.providers.exa.enabled=false"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isEmpty();
                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();
            });
    }

    @Test
    void readsApiKeyFromConfiguredEnvironmentProperty() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.providers.tavily.api-key-env=LYPI_TEST_TAVILY_KEY",
                "LYPI_TEST_TAVILY_KEY=test-key"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isPresent();
                assertThat(runtime.resolve("web_fetch")).isPresent();
            });
    }

    @Test
    void configuredMaxResultsControlsSearchToolSchema() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.max-results=7",
                "lypi.web.providers.tavily.api-key=test-key"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                Tool<?, ?> tool = runtime.resolve("web_search").orElseThrow();
                @SuppressWarnings("unchecked")
                Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
                @SuppressWarnings("unchecked")
                Map<String, Object> maxResults = (Map<String, Object>) properties.get("maxResults");

                assertThat(maxResults.get("maximum")).isEqualTo(7);
            });
    }

    @Test
    void registersSearchProvidersTogetherWithLocalFetch() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues(
                "lypi.web.enabled=true",
                "lypi.web.default-provider=brave",
                "lypi.web.providers.brave.api-key=brave-key",
                "lypi.web.providers.perplexity.api-key=perplexity-key"
            )
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isPresent();
                assertThat(runtime.resolve("web_fetch")).isPresent();
                assertThat(runtime.resolve("get_search_content")).isPresent();
            });
    }

    private static ContextSnapshot contextSnapshot() {
        return new ContextSnapshot(
            null,
            List.of(),
            null,
            null,
            AgentMode.EXECUTE,
            PermissionMode.BYPASS,
            new ContextBudget(0, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO)
        );
    }

    private static PermissionDecision allowAllSecurity(ToolUseRequest request, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.empty(),
            Map.of()
        );
    }

    private static void writeCachedFetch(Path runtimeCwd, String body) throws Exception {
        Path storeFile = runtimeCwd.resolve(".ly-pi").resolve("web-results.jsonl");
        Files.createDirectories(storeFile.getParent());
        Files.writeString(
            storeFile,
            """
            {"sessionId":"session","messageId":"message","responseId":"web_20260623_000001","sourceTool":"web_fetch","query":null,"url":"https://example.com/doc","items":[{"url":"https://example.com/doc","title":"Example","snippet":null,"content":"%s","format":"markdown","truncated":false,"source":"local"}],"createdAt":"2026-06-23T00:00:00Z"}
            """.formatted(body),
            StandardCharsets.UTF_8
        );
    }

    private static ToolResult<?> readCachedBody(ToolRuntimePort runtime) {
        return runtime.execute(
            List.of(new ToolUseRequest(
                "toolu_content",
                "get_search_content",
                Map.of("responseId", "web_20260623_000001"),
                "message"
            )),
            contextSnapshot(),
            new ToolRuntimeInvocation("session", "turn")
        ).getFirst();
    }
}
