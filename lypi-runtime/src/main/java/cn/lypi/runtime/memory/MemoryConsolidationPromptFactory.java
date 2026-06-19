package cn.lypi.runtime.memory;

import java.nio.file.Path;
import java.util.List;

/**
 * 生成后台记忆沉淀 turn 的用户输入。
 */
public final class MemoryConsolidationPromptFactory {
    /**
     * 返回后台沉淀语义。
     */
    public String prompt() {
        return prompt(null);
    }

    /**
     * 返回带沉淀前 memory 扫描摘要的后台沉淀语义。
     */
    public String prompt(MemoryPreflightScan preflightScan) {
        String preflightSection = formatPreflight(preflightScan);
        return """
            这是一次后台记忆沉淀任务，用户无需感知。

            请使用 `memory-settlement` skill 回顾当前 fork 分支中的长任务过程，判断是否存在值得长期保存的用户偏好、项目事实、团队约定、重要纠正或可复用处理经验。

            必须遵守 No Verification, No Memory：只有经过用户明确确认、文件读取、工具执行、测试结果或其他可追溯证据支持的信息，才允许写入 memory。

            只沉淀未来跨会话有长期价值的信息。不要保存临时状态、当前进度、一次性计划、命令流水、日志、diff、大段聊天记录或模型推理过程。不要保存敏感信息。

            写入前必须先读取已有 memory manifest 或索引，确认没有重复或冲突；优先更新旧 topic，不要创建重复 topic。`MEMORY.md` 或 `.ly-pi/memory.md` 只作为索引和治理入口，topic 文件必须带 frontmatter，并正确标注层级。

            %s

            优先小幅增量修改。若无可沉淀内容，请简短说明无可沉淀的原因，不要为了沉淀而沉淀。
            """.formatted(preflightSection);
    }

    private String formatPreflight(MemoryPreflightScan scan) {
        if (scan == null) {
            return "沉淀前 memory 扫描：未提供扫描摘要，请仍按固定路径读取已有 manifest 后再写入。";
        }
        return """
            沉淀前 memory 扫描：
            - manifest：%s
            - memory 文件：%s
            - 诊断：%s

            请把这些扫描结果作为写入前的导航信息：优先复用和修正已有文件；若诊断指出索引或 frontmatter 问题，可以在确有证据时一并修正。
            """.formatted(
            formatPaths(scan.manifestPaths()),
            formatPaths(scan.memoryPaths()),
            formatDiagnostics(scan.diagnostics())
        ).strip();
    }

    private String formatPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return "无";
        }
        return paths.stream()
            .limit(20)
            .map(Path::toString)
            .toList()
            .toString();
    }

    private String formatDiagnostics(List<MemoryLintDiagnostic> diagnostics) {
        if (diagnostics == null || diagnostics.isEmpty()) {
            return "无";
        }
        return diagnostics.stream()
            .limit(20)
            .map(diagnostic -> diagnostic.code() + "@" + diagnostic.path())
            .toList()
            .toString();
    }
}
