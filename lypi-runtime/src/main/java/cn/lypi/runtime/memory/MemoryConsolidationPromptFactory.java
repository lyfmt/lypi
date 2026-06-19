package cn.lypi.runtime.memory;

/**
 * 生成后台记忆沉淀 turn 的用户输入。
 */
public final class MemoryConsolidationPromptFactory {
    /**
     * 返回后台沉淀语义。
     */
    public String prompt() {
        return """
            这是一次后台记忆沉淀任务，用户无需感知。

            请使用 `memory-settlement` skill 回顾当前 fork 分支中的长任务过程，判断是否存在值得长期保存的用户偏好、项目事实、团队约定、重要纠正或可复用处理经验。

            必须遵守 No Verification, No Memory：只有经过用户明确确认、文件读取、工具执行、测试结果或其他可追溯证据支持的信息，才允许写入 memory。

            只沉淀未来跨会话有长期价值的信息。不要保存临时状态、当前进度、一次性计划、命令流水、日志、diff、大段聊天记录或模型推理过程。不要保存敏感信息。

            写入前必须先读取已有 memory manifest 或索引，确认没有重复或冲突；优先更新旧 topic，不要创建重复 topic。`MEMORY.md` 或 `.ly-pi/memory.md` 只作为索引和治理入口，topic 文件必须带 frontmatter，并正确标注层级。

            优先小幅增量修改。若无可沉淀内容，请简短说明无可沉淀的原因，不要为了沉淀而沉淀。
            """;
    }
}
