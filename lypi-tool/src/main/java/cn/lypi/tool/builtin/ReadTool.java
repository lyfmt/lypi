package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ReadTool extends AbstractFileTool {
    @Override
    public String name() {
        return "read";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("path"),
            "properties", Map.of(
                "path", Map.of("type", "string"),
                "offset", Map.of("type", "integer", "minimum", 1),
                "limit", Map.of("type", "integer", "minimum", 1)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("path") == null || input.get("path").toString().isBlank()) {
            return new ValidationResult(false, List.of("path 不能为空。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path path = resolvePath(input, context, "path");
            if (!Files.exists(path)) {
                return error(toolUseId, "文件不存在: " + relativePath(path, context));
            }
            requireRealPathInsideWorkspace(path, context);
            if (Files.isDirectory(path)) {
                return error(toolUseId, "不能读取目录: " + relativePath(path, context));
            }
            progress.progress(ToolProgress.phase("reading", "读取文件"));
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int offset = intInput(input, "offset", 1, 1, Math.max(1, lines.size()));
            int limit = intInput(input, "limit", lines.size(), 1, Math.max(1, lines.size()));
            int startIndex = offset - 1;
            int endIndex = Math.min(lines.size(), startIndex + limit);
            List<String> rendered = new ArrayList<>();
            rendered.add("File: " + relativePath(path, context));
            for (int index = startIndex; index < endIndex; index++) {
                rendered.add((index + 1) + " | " + lines.get(index));
            }
            progress.progress(new ToolProgress(
                cn.lypi.contracts.common.ToolProgressKind.STATUS,
                "read lines",
                relativePath(path, context),
                null,
                null,
                null,
                (long) (endIndex - startIndex),
                (long) lines.size(),
                null,
                Map.of()
            ));
            return success(toolUseId, String.join("\n", rendered));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "读取文件失败: " + exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return false;
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "read " + input;
    }
}
