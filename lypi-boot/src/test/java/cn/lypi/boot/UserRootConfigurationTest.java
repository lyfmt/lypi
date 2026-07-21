package cn.lypi.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.agent.compact.CompactionSummaryFallbackPolicy;
import cn.lypi.boot.ai.LyPiAiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class UserRootConfigurationTest {
    private static final String FALLBACK_POLICY = "lypi.ai.compaction-summary.fallback-policy";

    @TempDir
    Path tempDir;

    @Test
    void loadsUserRootConfigurationAndOverridesPackagedDefault() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Path configRoot = Files.createDirectories(home.resolve(".ly-pi"));
        Files.writeString(configRoot.resolve("application.yml"), """
            lypi:
              ai:
                compaction-summary:
                  fallback-policy: skip_compaction
            """);

        runner(home).run(context -> assertThat(boundPolicy(context))
            .isEqualTo(CompactionSummaryFallbackPolicy.SKIP_COMPACTION));
    }

    @Test
    void startsWhenUserRootConfigurationIsMissing() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("empty-home"));

        runner(home).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void externalRuntimeConfigurationOverridesUserRootConfiguration() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("override-home"));
        Path configRoot = Files.createDirectories(home.resolve(".ly-pi"));
        Files.writeString(configRoot.resolve("application.yml"), """
            lypi:
              ai:
                compaction-summary:
                  fallback-policy: skip_compaction
            """);
        Path runtimeConfig = Files.createDirectories(tempDir.resolve("runtime-config"));
        Files.writeString(runtimeConfig.resolve("application.yml"), """
            lypi:
              ai:
                compaction-summary:
                  fallback-policy: fallback_deterministic
            """);

        runner(home)
            .withSystemProperties("spring.config.additional-location=optional:" + runtimeConfig.toUri())
            .run(context -> assertThat(boundPolicy(context))
                .isEqualTo(CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC));
    }

    @Test
    void systemPropertyOverridesUserRootConfiguration() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("system-property-home"));
        Path configRoot = Files.createDirectories(home.resolve(".ly-pi"));
        Files.writeString(configRoot.resolve("application.yml"), """
            lypi:
              ai:
                compaction-summary:
                  fallback-policy: skip_compaction
            """);

        runner(home)
            .withSystemProperties(FALLBACK_POLICY + "=fallback_deterministic")
            .run(context -> assertThat(boundPolicy(context))
                .isEqualTo(CompactionSummaryFallbackPolicy.FALLBACK_DETERMINISTIC));
    }

    private ApplicationContextRunner runner(Path home) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(BoundPropertiesConfiguration.class)
            .withSystemProperties("user.home=" + home);
    }

    private CompactionSummaryFallbackPolicy boundPolicy(
        org.springframework.context.ApplicationContext context
    ) {
        return context.getBean(LyPiAiProperties.class)
            .getCompactionSummary()
            .getFallbackPolicy();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(LyPiAiProperties.class)
    static class BoundPropertiesConfiguration {
    }
}
