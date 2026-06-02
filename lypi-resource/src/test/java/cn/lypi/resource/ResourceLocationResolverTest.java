package cn.lypi.resource;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
}
