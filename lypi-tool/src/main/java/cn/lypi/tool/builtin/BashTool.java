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
import cn.lypi.contracts.runtime.SandboxRuntimePolicyKind;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolStateDelta;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.shell.DefaultSandboxPolicyResolver;
import cn.lypi.tool.shell.SandboxPlatformPaths;
import cn.lypi.tool.shell.SandboxPolicyOptions;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class BashTool extends AbstractFileTool {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(10);
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
                INPUT_SHELL, Map.of("type", "string", "enum", ALLOWED_SHELLS),
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
        if (!shell.isBlank() && !ALLOWED_SHELLS.contains(shell)) {
            return new ValidationResult(false, List.of("shell 仅支持 bash、sh 或 zsh。"));
        }
        if (input.containsKey("cwd")) {
            return new ValidationResult(false, List.of("cwd 由会话状态管理，不接受工具输入覆盖。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        if (sandboxPermissions(input) == SandboxPermissions.REQUIRE_ESCALATED) {
            return super.checkPermissions(input, context);
        }
        try {
            Path cwd = resolveBashCwd(context);
            return permissionPolicy.decide(input, context, cwd, permissionRuntimeState(context));
        } catch (RuntimeException | IOException exception) {
            return permissionPolicy.ask(input);
        }
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            rejectExecutionOnlyOverrides(input);
            Path cwd = resolveBashCwd(context);
            String shell = resolvedShell(input);
            boolean loginShell = booleanInput(input, INPUT_LOGIN_SHELL, true);
            Duration timeout = Duration.ofSeconds(intInput(input, "timeoutSeconds", (int) DEFAULT_TIMEOUT.toSeconds(), 1, 86_400));
            SandboxPermissions sandboxPermissions = sandboxPermissions(input);
            PermissionRuntimeState permissionRuntimeState = permissionRuntimeState(context);
            Optional<AdditionalPermissionProfile> additionalPermissions = additionalPermissionsForRequest(context, sandboxPermissions);
            Optional<String> justification = sandboxPermissions == SandboxPermissions.REQUIRE_ESCALATED
                ? Optional.of(stringInput(input, INPUT_JUSTIFICATION))
                : Optional.empty();
            SandboxRuntimePolicy basePolicy = usesHostExecution(permissionRuntimeState, sandboxPermissions, context)
                ? SandboxRuntimePolicy.disabled()
                : sandboxPolicy(context.workspaceRoot(), cwd, permissionRuntimeState, additionalPermissions);
            AbortSignal signal = abortSignal(context);
            shellHarness.importEnvFile(context.workspaceRoot(), context.sessionId(), System.getenv());
            progress.progress(ToolProgress.phase("running", "执行 shell 命令"));

            if (loginShell && !shellHarness.snapshotExists(context.workspaceRoot(), context.sessionId(), shell)) {
                shellHarness.prepareSnapshot(context.workspaceRoot(), context.sessionId(), shell).ifPresent(plan -> {
                    try (plan) {
                        SandboxRuntimePolicy snapshotPolicy = policyWithInternalAccess(
                            basePolicy,
                            cwd,
                            List.of(),
                            List.of(plan.captureFile())
                        );
                        ExecutionResult snapshotResult = executor.execute(
                            executionRequest(
                                plan.command(),
                                cwd,
                                shorterTimeout(timeout, SNAPSHOT_TIMEOUT),
                                snapshotPolicy,
                                sandboxPermissions,
                                additionalPermissions,
                                justification
                            ),
                            progress,
                            signal
                        );
                        shellHarness.completeSnapshot(plan, snapshotResult);
                    } catch (RuntimeException ignored) {
                        // Snapshot is an optimization; the user command falls back to a login shell.
                    }
                });
            }
            if (signal.aborted()) {
                return error(toolUseId, "命令执行已中止。");
            }

            try (ShellEnvironmentHarness.CommandPlan plan = shellHarness.prepareCommand(
                context.workspaceRoot(),
                context.sessionId(),
                shell,
                input.get("command").toString(),
                loginShell
            )) {
                SandboxRuntimePolicy commandPolicy = policyWithInternalAccess(
                    basePolicy,
                    cwd,
                    plan.readOnlyFiles(),
                    plan.writableFiles()
                );
                ExecutionResult result = executor.execute(
                    executionRequest(
                        plan.command(),
                        cwd,
                        timeout,
                        commandPolicy,
                        sandboxPermissions,
                        additionalPermissions,
                        justification
                    ),
                    progress,
                    signal
                );
                Optional<ToolStateDelta> delta = shellHarness.consumeCapturedCwd(
                    plan,
                    context.workspaceRoot(),
                    cwd
                ).filter(captured -> !captured.equals(cwd)).map(ToolStateDelta::new);
                return success(toolUseId, renderResult(result)).withStateDelta(delta);
            }
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

    private Path resolveBashCwd(ToolUseContext context) throws IOException {
        Path workspaceRoot = context.workspaceRoot().toAbsolutePath().normalize().toRealPath();
        Path cwd = context.cwd().toAbsolutePath().normalize().toRealPath();
        if (!Files.isDirectory(cwd) || !cwd.startsWith(workspaceRoot)) {
            throw new IOException("当前工作目录不在 workspace 内: " + context.cwd());
        }
        return cwd;
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

    private ExecutionRequest executionRequest(
        List<String> command,
        Path cwd,
        Duration timeout,
        SandboxRuntimePolicy sandboxPolicy,
        SandboxPermissions sandboxPermissions,
        Optional<AdditionalPermissionProfile> additionalPermissions,
        Optional<String> justification
    ) {
        return new ExecutionRequest(
            command,
            cwd,
            Map.of(),
            timeout,
            sandboxPolicy,
            sandboxPermissions,
            additionalPermissions,
            justification
        );
    }

    private SandboxRuntimePolicy policyWithInternalAccess(
        SandboxRuntimePolicy policy,
        Path cwd,
        List<Path> readOnlyFiles,
        List<Path> writableFiles
    ) {
        if (policy.kind() != SandboxRuntimePolicyKind.MANAGED) {
            return policy;
        }
        LinkedHashSet<Path> allowRead = new LinkedHashSet<>(
            policy.allowRead().isEmpty() ? SandboxPlatformPaths.defaultReadOnlyPaths() : policy.allowRead()
        );
        LinkedHashSet<Path> allowWrite = new LinkedHashSet<>(
            policy.allowWrite().isEmpty() ? List.of(cwd) : policy.allowWrite()
        );
        appendExistingFiles(allowRead, readOnlyFiles);
        appendExistingFiles(allowWrite, writableFiles);
        return new SandboxRuntimePolicy(
            policy.kind(),
            List.copyOf(allowRead),
            policy.denyRead(),
            List.copyOf(allowWrite),
            policy.denyWrite(),
            policy.networkMode(),
            policy.failIfUnavailable(),
            policy.autoAllowBashIfSandboxed()
        );
    }

    private void appendExistingFiles(LinkedHashSet<Path> target, List<Path> files) {
        for (Path file : files) {
            if (file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                target.add(file.toAbsolutePath().normalize());
            }
        }
    }

    private Duration shorterTimeout(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private void rejectExecutionOnlyOverrides(Map<String, Object> input) {
        if (input.containsKey("cwd")) {
            throw new IllegalArgumentException("cwd 由会话状态管理，不接受工具输入覆盖。");
        }
    }

    private String resolvedShell(Map<String, Object> input) {
        String shell = stringInput(input, INPUT_SHELL);
        String resolved = shell.isBlank() ? "bash" : shell;
        if (!ALLOWED_SHELLS.contains(resolved)) {
            throw new IllegalArgumentException("shell 仅支持 bash、sh 或 zsh。");
        }
        return resolved;
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
