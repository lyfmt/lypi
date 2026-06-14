package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class EditTool extends AbstractFileTool {
    @Override
    public String name() {
        return "edit";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("path", "oldString", "newString"),
            "properties", Map.of(
                "path", Map.of("type", "string"),
                "oldString", Map.of("type", "string"),
                "newString", Map.of("type", "string")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("path") == null || input.get("path").toString().isBlank()) {
            return new ValidationResult(false, List.of("path 不能为空。"));
        }
        if (!input.containsKey("oldString") || !input.containsKey("newString")) {
            return new ValidationResult(false, List.of("oldString 和 newString 不能为空。"));
        }
        if (input.get("oldString").toString().equals(input.get("newString").toString())) {
            return new ValidationResult(false, List.of("oldString 与 newString 不能相同。"));
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
            if (Files.isDirectory(path)) {
                return error(toolUseId, "不能编辑目录: " + relativePath(path, context));
            }
            String oldString = input.get("oldString").toString();
            String newString = input.get("newString").toString();
            if (oldString.equals(newString)) {
                return error(toolUseId, "oldString 与 newString 不能相同。");
            }
            String content = Files.readString(path);
            int first = content.indexOf(oldString);
            if (first < 0) {
                return error(toolUseId, "oldString 未在文件中出现。");
            }
            int second = content.indexOf(oldString, first + oldString.length());
            if (second >= 0) {
                return error(toolUseId, "oldString 在文件中出现多次，请提供更具体上下文。");
            }
            progress.progress(ToolProgress.phase("editing", "编辑文件"));
            String updated = content.replace(oldString, newString);
            writeAtomically(path, updated);
            progress.progress(ToolProgress.status("changed", relativePath(path, context)));
            return success(toolUseId, diff(relativePath(path, context), oldString, newString));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "编辑文件失败: " + exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return true;
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "edit " + input.get("path");
    }

    private String diff(String path, String oldString, String newString) {
        return "edit path=" + path + "\n"
            + "- " + oldString + "\n"
            + "+ " + newString;
    }
}
