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
    private static final String DEFAULT_APPLICATION_YML = """
        # ly-pi user-level configuration.
        # Project-specific runtime state belongs under <cwd>/.ly-pi.
        """;
    private static final String DEFAULT_MEMORY_INDEX = """
        # ly-pi Memory Index

        本文件是 L0 全局记忆入口，用于索引 L1 用户长期记忆。

        ## L1 Memories

        - `~/.ly-pi/memories/`: 用户跨项目长期指导、偏好和重要纠错。

        ## Memory Discipline

        - memory 是可演进经验源，不是稳定规范源。
        - 写入前必须有用户确认、文件读取、命令执行、测试结果或其他可追溯证据。
        - 不写临时状态、当前进度、未验证猜测或模型推理过程。
        """;
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
        return List.of(ensureDefaultUserRoot(Path.of(home).resolve(".ly-pi")));
    }

    private static List<Path> normalizeExistingRoots(List<Path> roots) {
        return roots.stream()
            .map(path -> path.toAbsolutePath().normalize())
            .filter(Files::isDirectory)
            .toList();
    }

    private static Path ensureDefaultUserRoot(Path root) {
        try {
            Path normalized = root.toAbsolutePath().normalize();
            Files.createDirectories(normalized);
            Files.createDirectories(normalized.resolve("memories"));
            Files.createDirectories(normalized.resolve("skills"));
            Files.createDirectories(normalized.resolve("prompts"));
            createFileIfMissing(normalized.resolve("application.yml"), DEFAULT_APPLICATION_YML);
            createFileIfMissing(normalized.resolve("memory.md"), DEFAULT_MEMORY_INDEX);
            return normalized;
        } catch (Exception exception) {
            return root;
        }
    }

    private static void createFileIfMissing(Path file, String content) throws Exception {
        if (Files.notExists(file)) {
            Files.writeString(file, content);
        }
    }
}
