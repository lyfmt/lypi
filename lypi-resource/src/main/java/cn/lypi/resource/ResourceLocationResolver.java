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

        - `~/.ly-pi/memory/`: 用户跨项目长期指导、偏好和重要纠错。

        ## Memory Discipline

        - memory 是可演进经验源，不是稳定规范源。
        - 写入前必须有用户确认、文件读取、命令执行、测试结果或其他可追溯证据。
        - 不写临时状态、当前进度、未验证猜测或模型推理过程。
        """;
    private static final String DEFAULT_MEMORY_SETTLEMENT_SKILL = """
        ---
        name: memory-settlement
        description: Use when a long task, important correction, repeated failure, project handoff, or reusable lesson may need durable memory.
        allowed_tools:
          - read
          - edit
          - write
        ---

        # Memory Settlement

        Use this skill to decide whether verified experience should be written to long-term memory.

        ## When To Run

        Run this skill before finishing when any of these occurred:
        - A long task or multi-step implementation produced reusable knowledge.
        - The user gave an important correction or durable preference.
        - Repeated failures revealed a non-obvious constraint or recovery path.
        - A project handoff, restart, or future continuation would otherwise repeat work.
        - A project direction item produced concrete project knowledge that should become a skill.

        Skip if there is no verified future value. Report the skip reason briefly.

        ## Gate

        No Verification, No Memory.

        Only write information backed by tool results, file reads, tests, command output, or explicit user confirmation. Do not write guesses, plans, temporary status, chat logs, current progress, timestamps, PIDs, generic knowledge, or model reasoning.

        ## Procedure

        1. Re-read the relevant current memory before editing:
           - L0: `~/.ly-pi/memory.md`
           - L1: the `~/.ly-pi/memory/*` file pointed to by L0 when the memory is user-level.
           - L2: `<cwd>/.ly-pi/memory.md` when updating project direction, boundaries, project-level corrections, principle-level facts, or L3 skill indexes.
           - L3: the relevant `<cwd>/.ly-pi/skills/*/SKILL.md` when updating concrete project knowledge, handling flows, troubleshooting techniques, implementation tradeoffs, or verification methods.
        2. Classify the memory:
           - L1 for cross-project user guidance, preferences, important corrections, and collaboration rules.
           - L2 for project direction: goals, boundaries, design direction, user corrections, principle-level facts, and indexes that point to L3 skills.
           - L3 for concrete project knowledge: module knowledge, handling flows, troubleshooting techniques, implementation tradeoffs, and verification methods.
        3. Ask the handoff question: if future context were reset, would missing this cause repeated investigation, repeated failure, or another user question?
        4. Patch the smallest useful text. Prefer one reusable sentence, rule, pointer, or skill update.
        5. Keep indexes synchronized:
           - If an L1 file is created, deleted, renamed, or rethemed, update L0.
           - L2 does not need an L0 pointer.
           - If a skill purpose or trigger changes, update its description or index metadata.

        ## Output

        After writing, state what changed and what verification justified it. If nothing was written, state why.
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
            Files.createDirectories(normalized.resolve("memory"));
            Files.createDirectories(normalized.resolve("skills"));
            Files.createDirectories(normalized.resolve("prompts"));
            createFileIfMissing(normalized.resolve("application.yml"), DEFAULT_APPLICATION_YML);
            createFileIfMissing(normalized.resolve("memory.md"), DEFAULT_MEMORY_INDEX);
            createFileIfMissing(
                normalized.resolve("skills").resolve("memory-settlement").resolve("SKILL.md"),
                DEFAULT_MEMORY_SETTLEMENT_SKILL
            );
            return normalized;
        } catch (Exception exception) {
            return root;
        }
    }

    private static void createFileIfMissing(Path file, String content) throws Exception {
        if (Files.notExists(file)) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, content);
        }
    }
}
