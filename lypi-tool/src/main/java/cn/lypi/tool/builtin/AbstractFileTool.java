package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.io.IOException;
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
        return ToolMessages.serializeForContext(output);
    }

    protected Path resolvePath(Map<String, Object> input, ToolUseContext context, String fieldName) {
        return WorkspacePaths.resolvePath(input, context, fieldName);
    }

    protected Path resolvePath(
        Map<String, Object> input,
        ToolUseContext context,
        String fieldName,
        FileSystemAccessMode accessMode
    ) {
        return WorkspacePaths.resolvePath(input, context, fieldName, accessMode);
    }

    protected String relativePath(Path path, ToolUseContext context) {
        return WorkspacePaths.relativePath(path, context);
    }

    protected Path requireRealPathInsideWorkspace(Path path, ToolUseContext context) throws IOException {
        return WorkspacePaths.requireRealPathInsideWorkspace(path, context);
    }

    protected Path requireRealPathInsideWorkspace(
        Path path,
        ToolUseContext context,
        FileSystemAccessMode accessMode
    ) throws IOException {
        return WorkspacePaths.requireRealPathInsideWorkspace(path, context, accessMode);
    }

    protected boolean realPathInsideWorkspace(Path path, ToolUseContext context) {
        return WorkspacePaths.realPathInsideWorkspace(path, context);
    }

    protected boolean realPathInsideWorkspace(Path path, ToolUseContext context, FileSystemAccessMode accessMode) {
        return WorkspacePaths.realPathInsideWorkspace(path, context, accessMode);
    }

    protected int intInput(Map<String, Object> input, String fieldName, int defaultValue, int min, int max) {
        return ToolInputs.intInput(input, fieldName, defaultValue, min, max);
    }

    protected ToolResult<String> success(String toolUseId, String text) {
        return ToolMessages.success(toolUseId, text);
    }

    protected ToolResult<String> error(String toolUseId, String message) {
        return ToolMessages.error(toolUseId, message);
    }

    protected String toolUseId(ToolUseContext context) {
        return ToolMessages.toolUseId(context);
    }

    protected void writeAtomically(Path path, String content) throws IOException {
        WorkspacePaths.writeAtomically(path, content);
    }
}
