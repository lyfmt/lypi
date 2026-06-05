package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BashTool extends AbstractFileTool {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final AbortSignal NOT_ABORTED = () -> false;
    private static final SandboxRuntimePolicy DEFAULT_SANDBOX_POLICY = new SandboxRuntimePolicy(
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        true,
        true
    );

    private final Executor executor;

    public BashTool(Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("command"),
            "properties", Map.of(
                "command", Map.of("type", "string"),
                "cwd", Map.of("type", "string"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("command") == null || input.get("command").toString().isBlank()) {
            return new ValidationResult(false, List.of("command 不能为空。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "执行 shell 命令需要确认。",
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name(), "command", input.getOrDefault("command", "").toString())
        );
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path cwd = resolvePath(input, context, "cwd");
            requireRealPathInsideWorkspace(cwd, context);
            Duration timeout = Duration.ofSeconds(intInput(input, "timeoutSeconds", (int) DEFAULT_TIMEOUT.toSeconds(), 1, 86_400));
            ExecutionRequest request = new ExecutionRequest(
                List.of("bash", "-lc", input.get("command").toString()),
                cwd,
                Map.of(),
                timeout,
                DEFAULT_SANDBOX_POLICY
            );
            progress.progress(ToolProgress.phase("running", "执行 shell 命令"));
            ExecutionResult result = executor.execute(request, progress, abortSignal(context));
            return success(toolUseId, renderResult(result));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "工作目录安全检查失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            return error(toolUseId, "命令执行失败: " + exception.getMessage());
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
        return "bash " + sanitizeCommand(input.getOrDefault("command", "").toString());
    }

    private AbortSignal abortSignal(ToolUseContext context) {
        Object value = context.metadata().get("abortSignal");
        return value instanceof AbortSignal signal ? signal : NOT_ABORTED;
    }

    private String renderResult(ExecutionResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("exitCode=").append(result.exitCode());
        if (result.timedOut()) {
            builder.append("\ntimedOut=true");
        }
        if (result.stdout() != null && !result.stdout().isBlank()) {
            builder.append("\nstdout:\n").append(result.stdout());
        }
        if (result.stderr() != null && !result.stderr().isBlank()) {
            builder.append("\nstderr:\n").append(result.stderr());
        }
        result.persistedOutput().ifPresent(path -> builder.append("\npersistedOutput=").append(path));
        return builder.toString();
    }

    private String sanitizeCommand(String command) {
        return command.replaceAll("(?i)(api[_-]?key|token|password)=\\S+", "$1=<redacted>");
    }
}
