package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GrepTool extends AbstractFileTool {
    private final RipgrepSearchRunner runner;
    private final GrepResultFormatter formatter;

    public GrepTool(Executor executor) {
        this(
            executor,
            new RipgrepSearchRunner(
                executor,
                new RipgrepCommandBuilder(),
                RipgrepBinaryResolver.defaults(),
                Duration.ofSeconds(20)
            ),
            new GrepResultFormatter()
        );
    }

    GrepTool(Executor executor, RipgrepSearchRunner runner, GrepResultFormatter formatter) {
        Objects.requireNonNull(executor, "executor must not be null");
        this.runner = Objects.requireNonNull(runner, "runner must not be null");
        this.formatter = Objects.requireNonNull(formatter, "formatter must not be null");
    }

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("pattern"),
            "properties", Map.ofEntries(
                Map.entry("pattern", Map.of("type", "string")),
                Map.entry("path", Map.of("type", "string")),
                Map.entry("glob", Map.of("type", "string")),
                Map.entry("output_mode", Map.of("type", "string", "enum", List.of("content", "files_with_matches", "count"))),
                Map.entry("-A", Map.of("type", "integer", "minimum", 0)),
                Map.entry("-B", Map.of("type", "integer", "minimum", 0)),
                Map.entry("-C", Map.of("type", "integer", "minimum", 0)),
                Map.entry("context", Map.of("type", "integer", "minimum", 0)),
                Map.entry("-n", Map.of("type", "boolean")),
                Map.entry("-i", Map.of("type", "boolean")),
                Map.entry("type", Map.of("type", "string")),
                Map.entry("head_limit", Map.of("type", "integer", "minimum", 0)),
                Map.entry("offset", Map.of("type", "integer", "minimum", 0)),
                Map.entry("multiline", Map.of("type", "boolean")),
                Map.entry("maxResults", Map.of("type", "integer", "minimum", 1))
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("pattern") == null || input.get("pattern").toString().isBlank()) {
            return new ValidationResult(false, List.of("pattern 不能为空。"));
        }
        try {
            GrepQuery.fromInput(input);
        } catch (RuntimeException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            GrepQuery query = GrepQuery.fromInput(input);
            Path root = resolvePath(input, context, "path");
            if (!Files.exists(root)) {
                return error(toolUseId, "搜索路径不存在: " + relativePath(root, context));
            }
            requireRealPathInsideWorkspace(root, context);
            progress.progress(ToolProgress.phase("scanning", "扫描文件"));
            RipgrepSearchResult searchResult = runner.search(query, root, context, progress);
            if (searchResult.isError()) {
                return error(toolUseId, searchResult.message());
            }
            progress.progress(ToolProgress.status("matched", Integer.toString(searchResult.lines().size())));
            return success(toolUseId, formatter.format(query, searchResult.lines(), context));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "搜索路径安全检查失败: " + exception.getMessage());
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
        Object pattern = input.getOrDefault("pattern", "");
        Object path = input.getOrDefault("path", ".");
        return "grep pattern=" + pattern + " path=" + path;
    }
}
