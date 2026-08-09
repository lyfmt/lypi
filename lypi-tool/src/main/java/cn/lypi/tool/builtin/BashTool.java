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
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
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
    private static final String METADATA_PERMISSION_APPROVED_FOR_HOST_EXECUTION = "permissionApprovedForHostExecution";
    private static final String METADATA_PERMISSION_MODE = "permissionMode";
    private static final String METADATA_PERMISSION_RUNTIME_STATE = "permissionRuntimeState";
    private static final List<String> ALLOWED_SHELLS = List.of("bash", "sh", "zsh");

    private final Executor executor;
    private final SandboxPolicyResolver sandboxPolicyResolver;
    private final BashPermissionPolicy permissionPolicy;
    private final ShellEnvironmentHarness shellHarness;

    public BashTool(Executor executor) {
        this(executor, new DefaultSandboxPolicyResolver(SandboxPolicyOptions.defaults()));
    }

    public BashTool(Executor executor, SandboxPolicyResolver sandboxPolicyResolver) {
        this(executor, sandboxPolicyResolver, new ShellEnvironmentHarness(ShellEnvironmentHarness.defaultStateRoot()));
    }

    public BashTool(Executor executor, SandboxPolicyResolver sandboxPolicyResolver, ShellEnvironmentHarness shellHarness) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.sandboxPolicyResolver = Objects.requireNonNull(sandboxPolicyResolver, "sandboxPolicyResolver must not be null");
        this.permissionPolicy = new BashPermissionPolicy(this.sandboxPolicyResolver);
        this.shellHarness = Objects.requireNonNull(shellHarness, "shellHarness must not be null");
    }

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Execute shell commands in the session's persistent shell state. "
            + "The working directory persists across calls: `cd dir` in one command applies to all subsequent "
            + "bash commands and file tools (read/write/grep/glob resolve relative paths against it), "
            + "so do not pass absolute paths or repeat cd. "
            + "Your login shell environment (aliases, functions, exports) is replayed from a snapshot on every call. "
            + "Note: `export`/`source` inside a command do NOT persist to the next call; cross-command environment "
            + "must come from session env scripts or the login profile.";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("command"),
            "properties", Map.of(
                "command", Map.of(
                    "type", "string",
                    "description", "Shell command executed in the session working directory (persists via cd)."
                ),
                INPUT_SHELL, Map.of("type", "string"),
                INPUT_LOGIN_SHELL, Map.of("type", "boolean"),
                "timeoutSeconds", Map.of("type", "integer", "minimum", 1),
                INPUT_SANDBOX_PERMISSIONS, Map.of(
                    "type", "string",
                    "enum", List.of("useDefault", "requireEscalated", "withAdditionalPermissions"),
                    "description",
                    "useDefault follows the active sandbox profile. requireEscalated asks to run outside the sandbox; "
                        + "the current permission mode decides the review route. withAdditionalPermissions uses "
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
        if (sandboxPermissions(input) == SandboxPermissions.REQUIRE_ESCALATED) {
            return super.checkPermissions(input, context);
        }
        try {
            Path cwd = resolveBashCwd(input, context);
            return permissionPolicy.decide(input, context, cwd, permissionRuntimeState(context));
        } catch (RuntimeException | IOException exception) {
            return permissionPolicy.ask(input);
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path cwd = resolveBashCwd(input, context);
            Duration timeout = Duration.ofSeconds(intInput(input, "timeoutSeconds", (int) DEFAULT_TIMEOUT.toSeconds(), 1, 86_400));
            SandboxPermissions sandboxPermissions = sandboxPermissions(input);
            PermissionRuntimeState permissionRuntimeState = permissionRuntimeState(context);
            Optional<AdditionalPermissionProfile> additionalPermissions = additionalPermissionsForRequest(context, sandboxPermissions);
            SandboxRuntimePolicy sandboxPolicy = usesHostExecution(permissionRuntimeState, sandboxPermissions, context)
                ? SandboxRuntimePolicy.disabled()
                : sandboxPolicy(context.workspaceRoot(), cwd, permissionRuntimeState, additionalPermissions);
            ExecutionRequest request = new ExecutionRequest(
                shellCommand(input, context),
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
            return success(toolUseId, renderResult(result, context));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "工作目录解析失败: " + exception.getMessage());
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

    private String renderResult(ExecutionResult result, ToolUseContext context) {
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
        shellHarness.consumeCapturedCwd(context.sessionId())
            .filter(captured -> !captured.equals(context.cwd().toAbsolutePath().normalize()))
            .ifPresent(captured -> builder.append("\nshellCwd=").append(captured));
        return builder.toString();
    }

    private String sanitizeCommand(String command) {
        return command.replaceAll("(?i)(api[_-]?key|token|password)=\\S+", "$1=<redacted>");
    }

    private SandboxPermissions sandboxPermissions(Map<String, Object> input) {
        return SandboxPermissions.fromToolValue(stringInput(input, INPUT_SANDBOX_PERMISSIONS));
    }

    private Path resolveBashCwd(Map<String, Object> input, ToolUseContext context) throws IOException {
        Path dynamicCwd = context.cwd().toAbsolutePath().normalize();
        String rawCwd = stringInput(input, "cwd");
        Path cwd = rawCwd.isBlank() ? dynamicCwd : Path.of(rawCwd);
        Path resolved = cwd.isAbsolute() ? cwd.toAbsolutePath().normalize() : dynamicCwd.resolve(cwd).normalize();
        return resolved.toRealPath();
    }

    private boolean usesHostExecution(
        PermissionRuntimeState permissionRuntimeState,
        SandboxPermissions sandboxPermissions,
        ToolUseContext context
    ) {
        return permissionRuntimeState.mode() == PermissionMode.BYPASS
            || permissionApprovedForHostExecution(context)
            || sandboxPermissions == SandboxPermissions.REQUIRE_ESCALATED;
    }

    private boolean permissionApprovedForHostExecution(ToolUseContext context) {
        Object value = context.metadata().get(METADATA_PERMISSION_APPROVED_FOR_HOST_EXECUTION);
        if (value instanceof Boolean approved) {
            return approved;
        }
        return value instanceof String approved && Boolean.parseBoolean(approved);
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
        PermissionRuntimeState permissionRuntimeState,
        Optional<AdditionalPermissionProfile> additionalPermissions
    ) {
        if (additionalPermissions.isPresent()) {
            return sandboxPolicyResolver.resolve(
                workspace,
                cwd,
                permissionRuntimeState,
                additionalPermissions.orElseThrow()
            );
        }
        return sandboxPolicyResolver.resolve(workspace, cwd, permissionRuntimeState);
    }

    private PermissionRuntimeState permissionRuntimeState(ToolUseContext context) {
        Object canonical = context.metadata().get(METADATA_PERMISSION_RUNTIME_STATE);
        if (canonical instanceof PermissionRuntimeState permissionRuntimeState) {
            return permissionRuntimeState;
        }
        Object legacy = context.metadata().get(METADATA_PERMISSION_MODE);
        if (legacy instanceof PermissionMode permissionMode) {
            return PermissionRuntimeState.forMode(permissionMode);
        }
        if (legacy instanceof String permissionMode && !permissionMode.isBlank()) {
            return PermissionRuntimeState.forMode(PermissionMode.fromJson(permissionMode));
        }
        return PermissionRuntimeState.forMode(PermissionMode.ASK);
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

    private List<String> shellCommand(Map<String, Object> input, ToolUseContext context) {
        String shell = stringInput(input, INPUT_SHELL);
        String resolvedShell = shell.isBlank() ? "bash" : shell;
        String command = input.get("command").toString();
        shellHarness.ensureSnapshot(context.sessionId(), resolvedShell);
        shellHarness.importEnvFile(context.sessionId(), System.getenv());
        String wrapped = shellHarness.wrap(context.sessionId(), command);
        boolean loginShell = booleanInput(input, INPUT_LOGIN_SHELL, true) && !shellHarness.snapshotExists(context.sessionId());
        return List.of(resolvedShell, loginShell ? "-lc" : "-c", wrapped);
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
