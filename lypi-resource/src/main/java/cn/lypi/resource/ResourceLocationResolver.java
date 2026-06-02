package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 解析资源发现位置和优先级。
 *
 * NOTE: 只保留已存在的用户目录和显式目录，避免后续扫描产生无意义诊断。
 */
class ResourceLocationResolver {
    private final List<Path> userRoots;
    private final List<Path> explicitRoots;

    ResourceLocationResolver() {
        this(defaultUserRoots(), List.of());
    }

    ResourceLocationResolver(List<Path> userRoots, List<Path> explicitRoots) {
        this.userRoots = normalizeExistingRoots(userRoots);
        this.explicitRoots = normalizeExistingRoots(explicitRoots);
    }

    /**
     * 根据项目根和当前目录生成资源发现计划。
     */
    ResourceDiscoveryPlan resolve(Path projectRoot, Path cwd) {
        Path normalizedProjectRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        List<ResourceLocation> locations = new ArrayList<>();
        List<ResourceDiagnostic> diagnostics = new ArrayList<>();

        int userIndex = 0;
        for (Path userRoot : userRoots) {
            locations.add(new ResourceLocation(ResourceLayer.USER, userRoot, 100 + userIndex, "user:" + userRoot));
            userIndex++;
        }

        locations.add(new ResourceLocation(ResourceLayer.PROJECT, normalizedProjectRoot, 200, "project"));

        int nestedIndex = 0;
        for (Path nestedRoot : nestedRoots(normalizedProjectRoot, normalizedCwd)) {
            locations.add(new ResourceLocation(
                ResourceLayer.NESTED_PROJECT,
                nestedRoot,
                300 + nestedIndex,
                "nested:" + nestedRoot
            ));
            nestedIndex++;
        }

        int explicitIndex = 0;
        for (Path explicitRoot : explicitRoots) {
            locations.add(new ResourceLocation(
                ResourceLayer.EXPLICIT_PATH,
                explicitRoot,
                400 + explicitIndex,
                "explicit:" + explicitRoot
            ));
            explicitIndex++;
        }

        return new ResourceDiscoveryPlan(normalizedProjectRoot, normalizedCwd, List.copyOf(locations), diagnostics);
    }

    private List<Path> nestedRoots(Path projectRoot, Path cwd) {
        if (!cwd.startsWith(projectRoot) || cwd.equals(projectRoot)) {
            return List.of();
        }
        List<Path> roots = new ArrayList<>();
        Path current = projectRoot;
        for (Path segment : projectRoot.relativize(cwd)) {
            current = current.resolve(segment);
            roots.add(current);
        }
        return roots;
    }

    private static List<Path> defaultUserRoots() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return List.of();
        }
        return List.of(Path.of(home).resolve(".ly-pi"));
    }

    private static List<Path> normalizeExistingRoots(List<Path> roots) {
        return roots.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::isDirectory)
            .toList();
    }
}
