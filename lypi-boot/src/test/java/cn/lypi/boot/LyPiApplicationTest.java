package cn.lypi.boot;

import cn.lypi.contracts.runtime.LyPiRuntime;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiApplicationTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesSpringBootMainMethod() throws Exception {
        Method main = LyPiApplication.class.getMethod("main", String[].class);

        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void startsApplicationWithDefaultRuntimeGraph() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LyPiApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .logStartupInfo(false)
            .properties(
                "lypi.runtime.cwd=" + tempDir,
                "lypi.runtime.session-id=application-startup",
                "lypi.runtime.transport=headless"
            )
            .run()) {

            assertThat(context.getBean(LyPiRuntime.class)).isNotNull();
            assertThat(tempDir.resolve(".lypi/sessions/application-startup.jsonl")).exists();
        }
    }
}
