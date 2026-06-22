package cn.lypi.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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
    void webToolsStayDisabledWhenEnabledWithoutProviderKey() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiToolAutoConfiguration.class)
            .withPropertyValues("lypi.web.enabled=true")
            .withBean(SecurityRuntimePort.class, () -> LyPiWebToolAutoConfigurationTest::allowAllSecurity)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("web_search")).isEmpty();
                assertThat(runtime.resolve("web_fetch")).isEmpty();
            });
    }

    @Test
    void registersTavilySearchAndFetchWhenApiKeyIsConfigured() {
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
            });
    }

    @Test
    void registersSearchOnlyProvidersTogether() {
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
                assertThat(runtime.resolve("web_fetch")).isEmpty();
            });
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
}
