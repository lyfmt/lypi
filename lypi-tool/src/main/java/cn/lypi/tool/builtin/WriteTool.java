package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class WriteTool extends AbstractFileTool {
    @Override
    public String name() {
        return "write";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("path", "content"),
            "properties", Map.of(
                "path", Map.of("type", "string"),
                "content", Map.of("type", "string"),
                "createParents", Map.of("type", "boolean")
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("path") == null || input.get("path").toString().isBlank()) {
            return new ValidationResult(false, List.of("path 不能为空。"));
        }
        if (!input.containsKey("content")) {
            return new ValidationResult(false, List.of("content 不能为空。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        try {
            Path path = resolvePath(input, context, "path");
            if (Files.exists(path)) {
                return ask("覆盖已有文件需要确认。", input);
            }
        } catch (IllegalArgumentException exception) {
            return deny(exception.getMessage(), input);
        }
        return super.checkPermissions(input, context);
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path path = resolvePath(input, context, "path");
            boolean createParents = Boolean.TRUE.equals(input.get("createParents"));
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                if (!createParents) {
                    return error(toolUseId, "父目录不存在: " + relativePath(parent, context));
                }
                Files.createDirectories(parent);
            }
            boolean overwritten = Files.exists(path);
            String content = input.get("content").toString();
            progress.progress(ToolProgress.phase("writing", "写入文件"));
            writeAtomically(path, content);
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            progress.progress(new ToolProgress(
                cn.lypi.contracts.common.ToolProgressKind.STATUS,
                "written bytes",
                relativePath(path, context),
                null,
                null,
                null,
                (long) bytes,
                (long) bytes,
                null,
                Map.of()
            ));
            return success(toolUseId, "write path=" + relativePath(path, context)
                + " bytes=" + bytes
                + " overwritten=" + overwritten);
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "写入文件失败: " + exception.getMessage());
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
        return "write " + input.get("path");
    }

    private PermissionDecision ask(String message, Map<String, Object> input) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name(), "path", input.getOrDefault("path", "").toString())
        );
    }

    private PermissionDecision deny(String message, Map<String, Object> input) {
        return new PermissionDecision(
            PermissionBehavior.DENY,
            PermissionDecisionReason.PATH_SAFETY,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name(), "path", input.getOrDefault("path", "").toString())
        );
    }
}
