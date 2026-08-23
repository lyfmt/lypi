package cn.lycode.boot;

import cn.lycode.contracts.runtime.LyCodeRuntime;
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

class LyCodeApplicationTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesSpringBootMainMethod() throws Exception {
        Method main = LyCodeApplication.class.getMethod("main", String[].class);

        assertThat(Modifier.isStatic(main.getModifiers())).isTrue();
        assertThat(main.getReturnType()).isEqualTo(void.class);
    }

    @Test
    void startsApplicationWithDefaultRuntimeGraph() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(LyCodeApplication.class)
            .web(WebApplicationType.NONE)
            .bannerMode(Banner.Mode.OFF)
            .logStartupInfo(false)
            .properties(
                "lycode.runtime.cwd=" + tempDir,
                "lycode.runtime.session-id=application-startup",
                "lycode.runtime.transport=headless"
            )
            .run()) {

            assertThat(context.getBean(LyCodeRuntime.class)).isNotNull();
            assertThat(tempDir.resolve(".ly-code/sessions/application-startup.jsonl")).exists();
        }
    }
}
