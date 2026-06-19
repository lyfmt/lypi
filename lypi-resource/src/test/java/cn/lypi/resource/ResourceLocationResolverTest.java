package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourceLocationResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolveBuildsLocationsInLayerOrderWithNestedDirectoriesFromShallowToDeep() throws Exception {
        Path userRoot = Files.createDirectories(tempDir.resolve("user/.ly-pi"));
        Path projectRoot = Files.createDirectories(tempDir.resolve("repo"));
        Path module = Files.createDirectories(projectRoot.resolve("module"));
        Path feature = Files.createDirectories(module.resolve("feature"));
        Path explicitRoot = Files.createDirectories(tempDir.resolve("explicit"));

        ResourceDiscoveryPlan plan = new ResourceLocationResolver(
            List.of(userRoot),
            List.of(explicitRoot)
        ).resolve(projectRoot, feature);

        assertThat(plan.locations())
            .extracting(location -> location.layer() + ":" + location.root())
            .containsExactly(
                ResourceLayer.USER + ":" + userRoot,
                ResourceLayer.PROJECT + ":" + projectRoot,
                ResourceLayer.NESTED_PROJECT + ":" + module,
                ResourceLayer.NESTED_PROJECT + ":" + feature,
                ResourceLayer.EXPLICIT_PATH + ":" + explicitRoot
            );
        assertThat(plan.locations())
            .extracting(ResourceLocation::priority)
            .containsExactly(100, 200, 300, 301, 400);
    }

    @Test
    void defaultResolverCreatesUserLyPiDefaultsWhenMissing() throws Exception {
        Path home = Files.createDirectories(tempDir.resolve("home"));
        Properties properties = System.getProperties();
        String previousHome = properties.getProperty("user.home");
        properties.setProperty("user.home", home.toString());
        try {
            ResourceDiscoveryPlan plan = new ResourceLocationResolver()
                .resolve(Files.createDirectories(tempDir.resolve("repo")), tempDir.resolve("repo"));

            Path userRoot = home.resolve(".ly-pi");
            assertThat(plan.locations())
                .extracting(ResourceLocation::root)
                .contains(userRoot.toAbsolutePath().normalize());
            assertThat(userRoot.resolve("application.yml")).exists();
            assertThat(userRoot.resolve("memory.md")).exists();
            assertThat(userRoot.resolve("memories")).isDirectory();
            assertThat(userRoot.resolve("skills")).isDirectory();
            assertThat(userRoot.resolve("prompts")).isDirectory();
            assertThat(userRoot.resolve("skills/memory-settlement/SKILL.md")).exists();
            assertThat(Files.readString(userRoot.resolve("skills/memory-settlement/SKILL.md")))
                .contains("name: memory-settlement")
                .contains("No Verification, No Memory");
            assertThat(userRoot.resolve("skills/memory-lint/SKILL.md")).doesNotExist();
            assertThat(userRoot.resolve("prompts/memory-lint.md")).doesNotExist();
        } finally {
            if (previousHome == null) {
                properties.remove("user.home");
            } else {
                properties.setProperty("user.home", previousHome);
            }
        }
    }
}
