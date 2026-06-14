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
           - L1: the file pointed to by L0 when the memory is user-level.
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
    private static final String DEFAULT_MEMORY_LINT_SKILL = """
        ---
        name: memory-lint
        description: Use when the user asks to lint, audit, clean up, repair, or self-heal layered memory across L0, L1, L2, or L3.
        allowed_tools:
          - read
          - edit
          - write
          - bash
        ---

        # Memory Lint

        Use this skill to audit and repair memory structure without turning memory into a transcript dump.

        ## Core Rules

        - No Verification, No Memory.
        - Never rewrite a whole memory file when a small patch is enough.
        - If a claim is not backed by file reads, command output, tests, or explicit user confirmation, report it as a suggestion instead of writing it.
        - Do not store volatile state, current progress, timestamps, PIDs, temporary paths, model reasoning, secrets, private data, long logs, or generic knowledge.
        - Keep upper layers as minimum sufficient pointers. Details belong in lower layers.

        ## Layer Contract

        - L0 `~/.ly-pi/memory.md`: global memory index and governance entry. It points to L1 and contains only triggers, indexes, and a few high-frequency red lines. It must not contain project facts or detailed SOPs.
        - L1 `~/.ly-pi/memories/*`: cross-project user preferences, collaboration rules, durable corrections, and reusable guidance. It is read on demand through L0.
        - L2 `<cwd>/.ly-pi/memory.md` or project `MEMORY.md`: project direction, goals, boundaries, user corrections, principle-level facts, and indexes pointing to L3 skills. It should not hold module how-to details.
        - L3 `<cwd>/.ly-pi/skills/*/SKILL.md`: concrete project knowledge, module workflows, troubleshooting steps, implementation tradeoffs, and verification methods.

        ## Lint Checklist

        For each selected layer:

        1. Locate candidate files. If a selected layer has no file, report it as missing only when the layer is expected for the current project.
        2. Read the current content before judging it.
        3. Classify every issue:
           - Duplicate content.
           - Conflicting facts.
           - Stale or unverifiable claims.
           - Wrong layer placement.
           - Missing upper-layer pointer.
           - Upper-layer entry containing how-to details.
           - L3 skill description not matching its trigger or body.
           - Content that should be deleted because it is volatile, sensitive, or purely session-local.
        4. Decide repair action:
           - Patch immediately only when evidence is clear and the patch is local.
           - Move content down a layer when upper-layer text contains concrete procedure.
           - Keep content but add conditions when both old and new facts are valid under different contexts.
           - Leave a recommendation when evidence is incomplete.
        5. After editing, re-read touched files and verify that duplicates, broken pointers, and formatting regressions were not introduced.

        ## Output Format

        Respond with:

        - `Checked`: selected layers and files read.
        - `Findings`: concise issue list.
        - `Changes`: files patched and evidence for each patch.
        - `Deferred`: suggestions not applied and why.
        """;
    private static final String DEFAULT_MEMORY_LINT_PROMPT = """
        ---
        name: memory-lint
        description: Lint and repair layered memory using the memory-lint skill.
        parameters:
          - name: layers
            description: Comma-separated memory layers to lint, such as L2,L3.
            required: true
        ---
        请使用 $memory-lint 对当前会话可访问的 memory 进行 lint 和必要的最小修补。

        目标层级：{{layers}}

        要求：

        1. 先按 `memory-lint` skill 的流程识别每个目标层级的文件位置和职责。
        2. 先读取目标文件，再判断问题；不要基于猜测修改 memory。
        3. 检查重复、冲突、过期、错层、缺少索引、索引失效、skill 描述与正文不一致、以及上层包含过多 how-to 细节的问题。
        4. 只修补已由文件读取、测试、命令输出或用户明确确认支持的问题。
        5. 修改必须是最小增量 patch；不确定是否该改时，列为建议，不写文件。
        6. 不写当前会话进度、临时状态、未验证猜测、模型推理过程、大段日志、密钥或隐私数据。
        7. 结束时输出：
           - 已检查层级。
           - 发现的问题。
           - 已修补内容及证据。
           - 未修补建议及原因。
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
            createFileIfMissing(
                normalized.resolve("skills").resolve("memory-settlement").resolve("SKILL.md"),
                DEFAULT_MEMORY_SETTLEMENT_SKILL
            );
            createFileIfMissing(
                normalized.resolve("skills").resolve("memory-lint").resolve("SKILL.md"),
                DEFAULT_MEMORY_LINT_SKILL
            );
            createFileIfMissing(
                normalized.resolve("prompts").resolve("memory-lint.md"),
                DEFAULT_MEMORY_LINT_PROMPT
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
