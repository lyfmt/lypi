package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
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
    private static final String INPUT_SANDBOX_PERMISSIONS = "sandboxPermissions";
    private static final String INPUT_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String INPUT_JUSTIFICATION = "justification";
    private static final String INPUT_SHELL = "shell";
    private static final String INPUT_LOGIN_SHELL = "loginShell";
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_APPROVED_ADDITIONAL_PERMISSIONS = "approvedAdditionalPermissions";
    private static final List<String> ALLOWED_SHELLS = List.of("bash", "sh", "zsh");

    private final Executor executor;
    private final SandboxPolicyResolver sandboxPolicyResolver;
    private final BashPermissionPolicy permissionPolicy;

    public BashTool(Executor executor) {
        this(executor, new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults()));
    }

    public BashTool(Executor executor, SandboxPolicyResolver sandboxPolicyResolver) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.sandboxPolicyResolver = Objects.requireNonNull(sandboxPolicyResolver, "sandboxPolicyResolver must not be null");
        this.permissionPolicy = new BashPermissionPolicy(this.sandboxPolicyResolver);
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
                INPUT_SHELL, Map.of("type", "string"),
                INPUT_LOGIN_SHELL, Map.of("type", "boolean"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1),
                INPUT_SANDBOX_PERMISSIONS, Map.of(
                    "type", "string",
                    "enum", List.of("useDefault", "requireEscalated", "withAdditionalPermissions"),
                    "description",
                    "useDefault follows the active sandbox profile. requireEscalated asks to run outside the sandbox; "
                        + "the approval policy decides whether a prompt is shown. withAdditionalPermissions uses "
                        + "permissions approved by request_permissions."
                ),
                INPUT_ADDITIONAL_PERMISSIONS, Map.of(
                    "type", "object",
                    "description", "Requested additional permissions; normally obtain these through request_permissions before running bash with sandboxPermissions=withAdditionalPermissions."
                ),
                INPUT_JUSTIFICATION, Map.of(
                    "type", "string",
                    "description", "required when sandboxPermissions=requireEscalated"
                ),
                "prefix_rule", Map.of(
                    "type", "array",
                    "minItems", 1,
                    "items", Map.of("type", "string")
                )
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("command") == null || input.get("command").toString().isBlank()) {
            return new ValidationResult(false, List.of("command 不能为空。"));
        }
        SandboxPermissions sandboxPermissions = sandboxPermissions(input);
        String justification = stringInput(input, INPUT_JUSTIFICATION);
        if (sandboxPermissions == SandboxPermissions.REQUIRE_ESCALATED && justification.isBlank()) {
            return new ValidationResult(false, List.of("sandboxPermissions=requireEscalated 时 justification 不能为空。"));
        }
        String shell = stringInput(input, INPUT_SHELL);
        if (!shell.isBlank() && !isAllowedShell(shell)) {
            return new ValidationResult(false, List.of("shell 仅支持 bash、sh、zsh 或 basename 为这些值的绝对路径。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        try {
            Path cwd = resolvePath(input, context, "cwd");
            requireRealPathInsideWorkspace(cwd, context);
            return permissionPolicy.decide(input, context, cwd);
        } catch (RuntimeException | IOException exception) {
            return permissionPolicy.ask(input);
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path cwd = resolvePath(input, context, "cwd");
            requireRealPathInsideWorkspace(cwd, context);
            Duration timeout = Duration.ofSeconds(intInput(input, "timeoutSeconds", (int) DEFAULT_TIMEOUT.toSeconds(), 1, 86_400));
            SandboxPermissions sandboxPermissions = sandboxPermissions(input);
            Optional<AdditionalPermissionProfile> additionalPermissions = additionalPermissionsForRequest(context, sandboxPermissions);
            SandboxRuntimePolicy sandboxPolicy = sandboxPolicy(context.cwd(), cwd, additionalPermissions);
            ExecutionRequest request = new ExecutionRequest(
                shellCommand(input),
                cwd,
                Map.of(),
                timeout,
                sandboxPolicy,
                sandboxPermissions,
                additionalPermissions,
                sandboxPermissions == SandboxPermissions.REQUIRE_ESCALATED
                    ? Optional.of(stringInput(input, INPUT_JUSTIFICATION))
                    : Optional.empty()
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
        if (result.metadata() != null && !result.metadata().executorName().isBlank()) {
            builder.append("\nexecutor=").append(result.metadata().executorName());
            builder.append("\nsandboxed=").append(result.metadata().sandboxed());
            if (result.metadata().sandboxDenied()) {
                builder.append("\nsandboxDenied=true");
            }
            if (result.metadata().sandboxUnavailable()) {
                builder.append("\nsandboxUnavailable=true");
            }
            result.metadata().retryWith().ifPresent(retryWith -> builder.append("\nretryWith=").append(retryWith));
            result.metadata().retryHint().ifPresent(retryHint -> builder.append("\nretryHint=").append(retryHint));
            result.metadata().diagnostic().ifPresent(diagnostic -> builder.append("\ndiagnostic=").append(diagnostic));
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

    private SandboxPermissions sandboxPermissions(Map<String, Object> input) {
        return SandboxPermissions.fromToolValue(stringInput(input, INPUT_SANDBOX_PERMISSIONS));
    }

    private Optional<AdditionalPermissionProfile> additionalPermissionsForRequest(
        ToolUseContext context,
        SandboxPermissions sandboxPermissions
    ) {
        if (sandboxPermissions != SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS) {
            return Optional.empty();
        }
        AdditionalPermissionProfile permissions = approvedAdditionalPermissions(context)
            .orElse(AdditionalPermissionProfile.empty());
        if (isEmpty(permissions)) {
            throw new IllegalArgumentException("sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。");
        }
        return Optional.of(permissions);
    }

    private SandboxRuntimePolicy sandboxPolicy(
        Path workspace,
        Path cwd,
        Optional<AdditionalPermissionProfile> additionalPermissions
    ) {
        if (additionalPermissions.isPresent()) {
            return sandboxPolicyResolver.resolve(workspace, cwd, additionalPermissions.orElseThrow());
        }
        return sandboxPolicyResolver.resolve(workspace, cwd);
    }

    private Optional<AdditionalPermissionProfile> approvedAdditionalPermissions(ToolUseContext context) {
        if (!approvedAdditionalPermissionsMarker(context)) {
            return Optional.empty();
        }
        Object value = context.metadata().get(METADATA_ADDITIONAL_PERMISSIONS);
        return value instanceof AdditionalPermissionProfile permissions ? Optional.of(permissions) : Optional.empty();
    }

    private boolean approvedAdditionalPermissionsMarker(ToolUseContext context) {
        Object value = context.metadata().get(METADATA_APPROVED_ADDITIONAL_PERMISSIONS);
        if (value instanceof Boolean approved) {
            return approved;
        }
        return value instanceof String approved && Boolean.parseBoolean(approved);
    }

    private boolean isEmpty(AdditionalPermissionProfile permissions) {
        return permissions.fileSystem().isEmpty() && permissions.network().isEmpty();
    }

    private List<String> shellCommand(Map<String, Object> input) {
        String shell = stringInput(input, INPUT_SHELL);
        String resolvedShell = shell.isBlank() ? "bash" : shell;
        boolean loginShell = booleanInput(input, INPUT_LOGIN_SHELL, true);
        return List.of(resolvedShell, loginShell ? "-lc" : "-c", input.get("command").toString());
    }

    private boolean isAllowedShell(String shell) {
        Path path = Path.of(shell);
        String shellName = path.getFileName() == null ? shell : path.getFileName().toString();
        if (path.isAbsolute()) {
            return ALLOWED_SHELLS.contains(shellName);
        }
        return ALLOWED_SHELLS.contains(shell);
    }

    private boolean booleanInput(Map<String, Object> input, String key, boolean defaultValue) {
        Object value = input == null ? null : input.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
