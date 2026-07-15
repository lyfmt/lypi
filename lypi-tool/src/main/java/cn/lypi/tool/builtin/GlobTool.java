package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.FileSystemAccessMode;
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
            Path root = resolvePath(input, context, "path", FileSystemAccessMode.READ);
            if (!Files.exists(root)) {
                return error(toolUseId, "匹配路径不存在: " + relativePath(root, context));
            }
            progress.progress(ToolProgress.phase("scanning", "扫描文件"));
            List<PathMatcher> matchers = matchers(pattern);
            List<String> matches;
            try (var walk = Files.walk(root)) {
                List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> !ignored(path))
                    .filter(path -> realPathInsideWorkspace(path, context, FileSystemAccessMode.READ))
                    .toList();
                progress.progress(ToolProgress.counter("files", files.size(), files.size()));
                matches = files.stream()
                    .filter(path -> matchesAny(matchers, root.relativize(path)))
                    .map(path -> relativePath(path, context))
                    .sorted()
                    .limit(maxResults)
                    .toList();
            }
            progress.progress(new ToolProgress(
                cn.lypi.contracts.common.ToolProgressKind.STATUS,
                "matched",
                String.join("\n", matches),
                null,
                null,
                null,
                (long) matches.size(),
                (long) maxResults,
                null,
                Map.of()
            ));
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
        if (input == null) {
            return "glob";
        }
        StringBuilder summary = new StringBuilder("glob");
        Object pattern = input.get("pattern");
        if (pattern != null && !pattern.toString().isBlank()) {
            summary.append(' ').append(pattern);
        }
        Object path = input.get("path");
        if (path != null && !path.toString().isBlank()) {
            summary.append(" in ").append(path);
        }
        return summary.toString();
    }

    private boolean ignored(Path path) {
        boolean insideLypi = false;
        for (Path part : path) {
            String value = part.toString();
            if ("target".equals(value)
                || ".git".equals(value)
                || ".agents".equals(value)
                || ".codex".equals(value)) {
                return true;
            }
            if (".lypi".equals(value)) {
                insideLypi = true;
                continue;
            }
            if (insideLypi && "sessions".equals(value)) {
                return true;
            }
            insideLypi = false;
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
