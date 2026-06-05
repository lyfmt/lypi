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
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class GrepTool extends AbstractFileTool {
    @Override
    public String name() {
        return "grep";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("pattern"),
            "properties", Map.of(
                "pattern", Map.of("type", "string"),
                "path", Map.of("type", "string"),
                "maxResults", Map.of("type", "integer", "minimum", 1)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("pattern") == null || input.get("pattern").toString().isBlank()) {
            return new ValidationResult(false, List.of("pattern 不能为空。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        String pattern = input.get("pattern").toString();
        int maxResults = intInput(input, "maxResults", 100, 1, 1_000);
        try {
            Path root = resolvePath(input, context, "path");
            if (!Files.exists(root)) {
                return error(toolUseId, "搜索路径不存在: " + relativePath(root, context));
            }
            progress.progress(ToolProgress.phase("scanning", "扫描文件"));
            StringBuilder output = new StringBuilder();
            int count = 0;
            List<Path> files;
            try (var walk = Files.walk(root)) {
                files = walk.filter(Files::isRegularFile)
                    .filter(path -> realPathInsideWorkspace(path, context))
                    .filter(path -> !ignored(path))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            }
            progress.progress(ToolProgress.counter("files", files.size(), files.size()));
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int index = 0; index < lines.size(); index++) {
                    if (!lines.get(index).contains(pattern)) {
                        continue;
                    }
                    output.append(relativePath(file, context))
                        .append(':')
                        .append(index + 1)
                        .append(':')
                        .append(lines.get(index))
                        .append('\n');
                    count++;
                    progress.progress(ToolProgress.status("matched", relativePath(file, context)));
                    if (count >= maxResults) {
                        return success(toolUseId, trimTrailingNewline(output));
                    }
                }
            }
            return success(toolUseId, trimTrailingNewline(output));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "搜索失败: " + exception.getMessage());
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
        return "grep " + input;
    }

    private boolean ignored(Path path) {
        for (Path part : path) {
            if ("target".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private String trimTrailingNewline(StringBuilder output) {
        if (!output.isEmpty() && output.charAt(output.length() - 1) == '\n') {
            output.deleteCharAt(output.length() - 1);
        }
        return output.toString();
    }
}
