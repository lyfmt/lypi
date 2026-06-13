package cn.lypi.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubagentCommandResolverTest {
    @Test
    void keepsConfiguredCommand() {
        LyPiSubagentProperties properties = new LyPiSubagentProperties();
        properties.setCommand(List.of("python3", "child.py"));

        List<String> command = new SubagentCommandResolver(properties, () -> Path.of("/tmp/app.jar").toUri()).resolve();

        assertThat(command).containsExactly("python3", "child.py");
    }

    @Test
    void infersHeadlessCommandFromPackagedJarLocation() {
        LyPiSubagentProperties properties = new LyPiSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/opt/lypi/lypi-boot.jar").toUri()
        ).resolve();

        assertThat(command).containsExactly("java", "-jar", "/opt/lypi/lypi-boot.jar", "headless-subagent");
    }

    @Test
    void fallsBackToJavaJarCommandWhenCodeSourceIsNotJarLocation() {
        LyPiSubagentProperties properties = new LyPiSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/tmp/spring-boot-loader/").toUri(),
            () -> "/opt/lypi/lypi-boot.jar --lypi.runtime.default-model=gpt-5.4-mini"
        ).resolve();

        assertThat(command).containsExactly("java", "-jar", "/opt/lypi/lypi-boot.jar", "headless-subagent");
    }

    @Test
    void doesNotInferCommandFromClasspathDirectory() {
        LyPiSubagentProperties properties = new LyPiSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/repo/lypi-boot/target/classes/").toUri(),
            () -> ""
        ).resolve();

        assertThat(command).isEmpty();
    }

    @Test
    void doesNotInferCommandFromNonJarLocation() {
        LyPiSubagentProperties properties = new LyPiSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> URI.create("file:/repo/lypi-boot/target/lypi-boot.bin")
        ).resolve();

        assertThat(command).isEmpty();
    }
}
