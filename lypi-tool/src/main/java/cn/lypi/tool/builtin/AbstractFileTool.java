package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

abstract class AbstractFileTool implements Tool<Map<String, Object>, String> {
    private static final int DEFAULT_MAX_RESULT_SIZE = 16_384;

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of()
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "工具自身允许，路径硬安全线由安全运行时判定。",
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name())
        );
    }

    @Override
    public InterruptBehavior interruptBehavior() {
        return InterruptBehavior.CANCEL;
    }

    @Override
    public int maxResultSize() {
        return DEFAULT_MAX_RESULT_SIZE;
    }

    @Override
    public AgentMessage serializeForContext(String output) {
        return toolMessage("toolu_unknown", output, false);
    }

    protected Path resolvePath(Map<String, Object> input, ToolUseContext context, String fieldName) {
        Object raw = input.get(fieldName);
        String value = raw == null ? "." : raw.toString();
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path resolved = cwd.resolve(value).normalize();
        if (!resolved.startsWith(cwd)) {
            throw new IllegalArgumentException("路径越过当前工作目录: " + value);
        }
        return resolved;
    }

    protected String relativePath(Path path, ToolUseContext context) {
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(cwd)) {
            return normalized.toString();
        }
        String relative = cwd.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    protected Path requireRealPathInsideWorkspace(Path path, ToolUseContext context) throws IOException {
        Path realCwd = context.cwd().toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realCwd)) {
            throw new IllegalArgumentException("路径经符号链接越过当前工作目录: " + relativePath(path, context));
        }
        return realPath;
    }

    protected boolean realPathInsideWorkspace(Path path, ToolUseContext context) {
        try {
            Path realCwd = context.cwd().toRealPath();
            Path realPath = path.toRealPath();
            return realPath.startsWith(realCwd);
        } catch (IOException exception) {
            return false;
        }
    }

    protected int intInput(Map<String, Object> input, String fieldName, int defaultValue, int min, int max) {
        Object value = input.get(fieldName);
        int parsed = switch (value) {
            case null -> defaultValue;
            case Number number -> number.intValue();
            default -> Integer.parseInt(value.toString());
        };
        return Math.max(min, Math.min(max, parsed));
    }

    protected ToolResult<String> success(String toolUseId, String text) {
        return new ToolResult<>(text, false, List.of(toolMessage(toolUseId, text, false)), Optional.empty());
    }

    protected ToolResult<String> error(String toolUseId, String message) {
        String text = message == null || message.isBlank() ? "工具调用失败。" : message;
        return new ToolResult<>(text, true, List.of(toolMessage(toolUseId, text, true)), Optional.empty());
    }

    protected String toolUseId(ToolUseContext context) {
        Object value = context.metadata().get("toolUseId");
        return value == null ? "toolu_unknown" : value.toString();
    }

    protected void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        Path temp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
    }

    private AgentMessage toolMessage(String toolUseId, String text, boolean error) {
        return new AgentMessage(
            "msg_" + toolUseId,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(toolUseId, text, error)),
            Instant.now(),
            Optional.empty(),
            Optional.empty()
        );
    }
}
