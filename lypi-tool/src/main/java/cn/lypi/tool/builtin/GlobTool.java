package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GlobTool extends AbstractFileTool {
    @Override
    public String name() {
        return "glob";
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
                return error(toolUseId, "匹配路径不存在: " + relativePath(root, context));
            }
            List<PathMatcher> matchers = matchers(pattern);
            List<String> matches;
            try (var walk = Files.walk(root)) {
                matches = walk.filter(Files::isRegularFile)
                    .filter(path -> realPathInsideWorkspace(path, context))
                    .filter(path -> !ignored(path))
                    .filter(path -> matchesAny(matchers, root.relativize(path)))
                    .map(path -> relativePath(path, context))
                    .sorted()
                    .limit(maxResults)
                    .toList();
            }
            return success(toolUseId, String.join("\n", matches));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "文件匹配失败: " + exception.getMessage());
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
        return "glob " + input;
    }

    private boolean ignored(Path path) {
        for (Path part : path) {
            if ("target".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private List<PathMatcher> matchers(String pattern) {
        List<String> patterns = new ArrayList<>();
        patterns.add(pattern);
        if (pattern.contains("**/")) {
            patterns.add(pattern.replace("**/", ""));
        }
        return patterns.stream()
            .distinct()
            .map(value -> FileSystems.getDefault().getPathMatcher("glob:" + value))
            .toList();
    }

    private boolean matchesAny(List<PathMatcher> matchers, Path relativePath) {
        return matchers.stream().anyMatch(matcher -> matcher.matches(relativePath));
    }
}
