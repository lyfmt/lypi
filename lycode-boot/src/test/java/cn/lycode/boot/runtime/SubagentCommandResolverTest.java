package cn.lycode.boot.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubagentCommandResolverTest {
    @Test
    void keepsConfiguredCommand() {
        LyCodeSubagentProperties properties = new LyCodeSubagentProperties();
        properties.setCommand(List.of("python3", "child.py"));

        List<String> command = new SubagentCommandResolver(properties, () -> Path.of("/tmp/app.jar").toUri()).resolve();

        assertThat(command).containsExactly("python3", "child.py");
    }

    @Test
    void infersHeadlessCommandFromPackagedJarLocation() {
        LyCodeSubagentProperties properties = new LyCodeSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/opt/lycode/lycode-boot.jar").toUri()
        ).resolve();

        assertThat(command).containsExactly("java", "-jar", "/opt/lycode/lycode-boot.jar", "headless-subagent");
    }

    @Test
    void fallsBackToJavaJarCommandWhenCodeSourceIsNotJarLocation() {
        LyCodeSubagentProperties properties = new LyCodeSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/tmp/spring-boot-loader/").toUri(),
            () -> "/opt/lycode/lycode-boot.jar --lycode.runtime.default-model=gpt-5.4-mini"
        ).resolve();

        assertThat(command).containsExactly("java", "-jar", "/opt/lycode/lycode-boot.jar", "headless-subagent");
    }

    @Test
    void doesNotInferCommandFromClasspathDirectory() {
        LyCodeSubagentProperties properties = new LyCodeSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> Path.of("/repo/lycode-boot/target/classes/").toUri(),
            () -> ""
        ).resolve();

        assertThat(command).isEmpty();
    }

    @Test
    void doesNotInferCommandFromNonJarLocation() {
        LyCodeSubagentProperties properties = new LyCodeSubagentProperties();

        List<String> command = new SubagentCommandResolver(
            properties,
            () -> URI.create("file:/repo/lycode-boot/target/lycode-boot.bin")
        ).resolve();

        assertThat(command).isEmpty();
    }
}
