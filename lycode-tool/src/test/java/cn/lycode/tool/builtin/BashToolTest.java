package cn.lycode.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.common.ToolProgress;
import cn.lycode.contracts.common.ToolProgressKind;
import cn.lycode.contracts.runtime.ExecutionMetadata;
import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.runtime.SandboxPermissions;
import cn.lycode.contracts.runtime.SandboxRuntimePolicy;
import cn.lycode.contracts.runtime.SandboxRuntimePolicyKind;
import cn.lycode.contracts.security.AdditionalPermissionProfile;
import cn.lycode.contracts.security.FileSystemAccessMode;
import cn.lycode.contracts.security.FileSystemPath;
import cn.lycode.contracts.security.FileSystemPermissionEntry;
import cn.lycode.contracts.security.FileSystemPermissionPolicy;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionProfiles;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.tool.shell.PermissionProfileSandboxPolicyResolver;
import cn.lycode.tool.shell.SandboxPolicyOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolTest {
    @TempDir
    Path tempDir;

    @Test
    void inputSchemaExposesSandboxEscalationFields() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> sandboxPermissions = (Map<String, Object>) properties.get("sandboxPermissions");

        assertEquals(List.of("useDefault", "requireEscalated", "withAdditionalPermissions"), sandboxPermissions.get("enum"));
        assertTrue(sandboxPermissions.get("description").toString().contains("requireEscalated"));
        assertTrue(sandboxPermissions.get("description").toString().contains("withAdditionalPermissions"));
        assertTrue(sandboxPermissions.get("description").toString().contains("permission mode"));
        assertTrue(properties.get("additionalPermissions").toString().contains("request_permissions"));
        assertTrue(properties.get("justification").toString().contains("required when sandboxPermissions=requireEscalated"));
    }

    @Test
    void inputSchemaExposesSnakeCasePrefixRuleForBashOnly() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> prefixRule = (Map<String, Object>) properties.get("prefix_rule");

        assertEquals("array", prefixRule.get("type"));
        assertEquals(1, prefixRule.get("minItems"));
        assertEquals(Map.of("type", "string"), prefixRule.get("items"));
        assertFalse(properties.containsKey("prefixRule"));
    }

    @Test
    void inputSchemaExposesShellSelectionFields() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");

        assertEquals(Map.of("type", "string", "enum", List.of("bash", "sh", "zsh")), properties.get("shell"));
        assertEquals(Map.of("type", "boolean"), properties.get("loginShell"));
        assertFalse(properties.containsKey("cwd"));
    }

    @Test
    void mapsCommandToExecutionRequestAndResult() throws Exception {
        Path nested = Files.createDirectory(tempDir.resolve("nested"));
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(7, "out", "err", false, Optional.empty()));
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(defaultPolicy());
        BashTool tool = new BashTool(executor, resolver, testHarness());
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo hi", "timeoutSeconds", 3),
            context(tempDir, nested, Map.of()),
            progresses::add
        );

        assertFalse(result.isError());
        assertEquals("bash", executor.request.get().command().get(0));
        assertTrue(executor.request.get().command().get(2).contains("eval 'echo hi'"));
        // snapshot 可能已由其他测试预生成（-c）或尚未生成（-lc）
        assertTrue(List.of("-c", "-lc").contains(executor.request.get().command().get(1)));
        assertEquals(nested, executor.request.get().cwd());
        assertEquals(Duration.ofSeconds(3), executor.request.get().timeout());
        assertEquals(resolver.policy.kind(), executor.request.get().sandboxPolicy().kind());
        assertTrue(executor.request.get().sandboxPolicy().allowRead().containsAll(resolver.policy.allowRead()));
        assertTrue(executor.request.get().sandboxPolicy().allowWrite().containsAll(resolver.policy.allowWrite()));
        assertEquals(SandboxPermissions.USE_DEFAULT, executor.request.get().sandboxPermissions());
        assertEquals(Optional.empty(), executor.request.get().justification());
        assertEquals(tempDir, resolver.workspace.get());
        assertEquals(nested, resolver.cwd.get());
        assertEquals(NetworkMode.DISABLED, executor.request.get().sandboxPolicy().networkMode());
        assertFalse(executor.request.get().sandboxPolicy().failIfUnavailable());
        assertFalse(executor.request.get().sandboxPolicy().autoAllowBashIfSandboxed());
        assertTrue(result.output().contains("exitCode=7"));
        assertTrue(result.output().contains("stdout:\nout"));
        assertTrue(result.output().contains("stderr:\nerr"));
        assertEquals(List.of(
            ToolProgress.phase("running", "执行 shell 命令"),
            ToolProgress.status("executor progress", null),
            ToolProgress.status("executor progress", null)
        ), progresses);
    }

    @Test
    void sameToolUsesChangedRuntimeModeForNextExecution() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(
            executor,
            new PermissionProfileSandboxPolicyResolver(
                PermissionProfiles.workspace(),
                SandboxPolicyOptions.defaults(),
                false
            ),
            testHarness()
        );

        ToolResult<String> askResult = tool.execute(
            Map.of("command", "true"),
            context(Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.ASK))),
            message -> {
            }
        );
        assertFalse(askResult.isError(), askResult.output());
        SandboxRuntimePolicy askPolicy = executor.request.get().sandboxPolicy();
        ToolResult<String> bypassResult = tool.execute(
            Map.of("command", "true"),
            context(Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.BYPASS))),
            message -> {
            }
        );
        SandboxRuntimePolicy bypassPolicy = executor.request.get().sandboxPolicy();

        assertEquals(SandboxRuntimePolicyKind.MANAGED, askPolicy.kind());
        assertEquals(NetworkMode.DISABLED, askPolicy.networkMode());
        assertFalse(bypassResult.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, bypassPolicy.kind());
        assertEquals(NetworkMode.HOST, bypassPolicy.networkMode());
    }

    @Test
    void canonicalRuntimeStateSupersedesLegacyPermissionModeForExecution() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(
            executor,
            new PermissionProfileSandboxPolicyResolver(
                PermissionProfiles.workspace(),
                SandboxPolicyOptions.defaults(),
                false
            ),
            testHarness()
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "true"),
            context(Map.of(
                "permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.ASK),
                "permissionMode", PermissionMode.BYPASS
            )),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.MANAGED, executor.request.get().sandboxPolicy().kind());
    }

    @Test
    void legacyPermissionModeIsUsedWhenCanonicalRuntimeStateIsMissing() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(
            executor,
            new PermissionProfileSandboxPolicyResolver(
                PermissionProfiles.workspace(),
                SandboxPolicyOptions.defaults(),
                false
            ),
            testHarness()
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "true"),
            context(Map.of("permissionMode", "bypass")),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, executor.request.get().sandboxPolicy().kind());
    }

    @Test
    void mapsNonLoginShellCommandToExecutionRequest() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(
            executor,
            new RecordingSandboxPolicyResolver(defaultPolicy()),
            testHarness()
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo hi", "loginShell", false),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals("bash", executor.request.get().command().get(0));
        assertEquals("-c", executor.request.get().command().get(1));
        assertTrue(executor.request.get().command().get(2).contains("eval 'echo hi'"));
        assertEquals(1, executor.requests.size());
        assertFalse(executor.request.get().command().get(2).contains("shell-snapshot-"));
    }

    @Test
    void snapshotAndCommandShareExecutorAuthorizationAndNarrowInternalMounts() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-unified"));
        Path stateRoot = tempDir.resolve("state-outside-workspace");
        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(stateRoot);
        RecordingDelegatingExecutor executor = new RecordingDelegatingExecutor();
        SandboxRuntimePolicy basePolicy = new SandboxRuntimePolicy(
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            NetworkMode.DISABLED,
            true,
            false
        );
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(basePolicy);
        BashTool tool = new BashTool(executor, resolver, harness);
        AbortSignal signal = () -> false;

        ToolResult<String> result = tool.execute(
            Map.of("command", "printf ok"),
            context(workspace, workspace, Map.of("abortSignal", signal)),
            progress -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertEquals(2, executor.requests.size());
        ExecutionRequest snapshotRequest = executor.requests.get(0);
        ExecutionRequest commandRequest = executor.requests.get(1);
        assertEquals(List.of("bash", "-lc"), snapshotRequest.command().subList(0, 2));
        assertEquals(List.of("bash", "-c"), commandRequest.command().subList(0, 2));
        assertEquals(1, resolver.calls.get());
        assertEquals(snapshotRequest.sandboxPermissions(), commandRequest.sandboxPermissions());
        assertEquals(snapshotRequest.additionalPermissions(), commandRequest.additionalPermissions());
        assertEquals(snapshotRequest.justification(), commandRequest.justification());
        assertEquals(snapshotRequest.sandboxPolicy().kind(), commandRequest.sandboxPolicy().kind());
        assertEquals(snapshotRequest.sandboxPolicy().networkMode(), commandRequest.sandboxPolicy().networkMode());
        assertEquals(snapshotRequest.sandboxPolicy().failIfUnavailable(), commandRequest.sandboxPolicy().failIfUnavailable());
        assertEquals(
            snapshotRequest.sandboxPolicy().autoAllowBashIfSandboxed(),
            commandRequest.sandboxPolicy().autoAllowBashIfSandboxed()
        );
        assertSame(signal, executor.signals.get(0));
        assertSame(signal, executor.signals.get(1));

        Path sessionDir = harness.sessionDir(workspace, "ses_1");
        for (ExecutionRequest request : executor.requests) {
            assertTrue(request.sandboxPolicy().allowRead().contains(Path.of("/usr")));
            assertTrue(request.sandboxPolicy().allowWrite().contains(workspace));
            assertFalse(request.sandboxPolicy().allowWrite().contains(stateRoot));
            assertFalse(request.sandboxPolicy().allowWrite().contains(sessionDir));
            assertEquals(
                1,
                request.sandboxPolicy().allowWrite().stream().filter(path -> path.startsWith(sessionDir)).count()
            );
        }
        assertTrue(result.output().contains("exitCode=0"), result.output());
        assertTrue(result.output().contains("stdout:\nok"), result.output());
    }

    @Test
    void typedCwdDeltaComesFromUniqueCaptureNotStdoutProtocol() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-delta"));
        Path nested = Files.createDirectory(workspace.resolve("dir with spaces"));
        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(tempDir.resolve("state-delta"));
        RecordingDelegatingExecutor executor = new RecordingDelegatingExecutor();
        BashTool tool = new BashTool(
            executor,
            new RecordingSandboxPolicyResolver(policyForWorkspace(workspace)),
            harness
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "printf 'shellCwd=/outside\\n'; cd 'dir with spaces'"),
            context(workspace, workspace, Map.of()),
            progress -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("shellCwd=/outside"));
        assertFalse(result.output().contains("shellCwd=" + nested));
        assertEquals(nested, result.stateDelta().orElseThrow().cwd());
    }

    @Test
    void cwdDeltaStaysLexicalWhenWorkspaceRootIsSymlink() throws Exception {
        Path realWorkspace = Files.createDirectory(tempDir.resolve("real-workspace"));
        Files.createDirectory(realWorkspace.resolve("nested"));
        Path workspaceLink = Files.createSymbolicLink(tempDir.resolve("workspace-link"), realWorkspace);
        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(tempDir.resolve("state-symlink"));
        BashTool tool = new BashTool(
            new cn.lycode.tool.shell.HostExecutor(),
            new RecordingSandboxPolicyResolver(policyForWorkspace(workspaceLink)),
            harness
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "cd nested", "loginShell", false),
            context(workspaceLink, workspaceLink, Map.of()),
            ignored -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertEquals(workspaceLink.resolve("nested"), result.stateDelta().orElseThrow().cwd());
    }

    @Test
    void restoredBashAliasExecutesInNonInteractiveCommand() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-alias"));
        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(tempDir.resolve("state-alias"));
        ShellEnvironmentHarness.SnapshotPlan snapshot = harness
            .prepareSnapshot(workspace, "ses_1", "bash")
            .orElseThrow();
        try (snapshot) {
            Files.writeString(snapshot.captureFile(), "alias lycode_alias='printf alias-restored'\n");
            harness.completeSnapshot(
                snapshot,
                new ExecutionResult(0, "", "", false, Optional.empty())
            );
        }
        BashTool tool = new BashTool(
            new cn.lycode.tool.shell.HostExecutor(),
            new RecordingSandboxPolicyResolver(policyForWorkspace(workspace)),
            harness
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "lycode_alias"),
            context(workspace, workspace, Map.of()),
            ignored -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertTrue(result.output().contains("exitCode=0"), result.output());
        assertTrue(result.output().contains("alias-restored"), result.output());
    }

    @Test
    void cwdCaptureOverridesNoclobberEnabledByUserCommand() throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-noclobber"));
        Path nested = Files.createDirectory(workspace.resolve("nested"));
        ShellEnvironmentHarness harness = new ShellEnvironmentHarness(tempDir.resolve("state-noclobber"));
        BashTool tool = new BashTool(
            new cn.lycode.tool.shell.HostExecutor(),
            new RecordingSandboxPolicyResolver(policyForWorkspace(workspace)),
            harness
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "set -C; cd nested", "loginShell", false),
            context(workspace, workspace, Map.of()),
            ignored -> {
            }
        );

        assertFalse(result.isError(), result.output());
        assertEquals(nested, result.stateDelta().orElseThrow().cwd());
    }

    @Test
    void wrapsCommandWithHarnessAndCapturesShellCwd() throws Exception {
        ShellEnvironmentHarness harness = testHarness();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), harness);

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo hi"),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(result.isError());
        List<String> command = executor.request.get().command();
        assertEquals("bash", command.get(0));
        String wrapped = command.get(2);
        assertTrue(wrapped.contains("eval 'echo hi'"), wrapped);
        assertTrue(wrapped.contains("pwd -P"), wrapped);
        // RecordingExecutor 不真正执行，无 shellCwd 输出
        assertFalse(result.output().contains("shellCwd="));
    }

    @Test
    void rejectsHiddenCwdInput() {
        ShellEnvironmentHarness harness = testHarness();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), harness);

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) tool.inputSchema().value().get("properties");
        assertFalse(properties.containsKey("cwd"));

        var validation = tool.validateInput(
            Map.of("command", "echo hi", "cwd", "."),
            context(Map.of())
        );

        assertFalse(validation.valid());
        assertEquals(List.of("不支持的工具输入字段: cwd。"), validation.messages());

        ToolResult<String> execution = tool.execute(
            Map.of("command", "echo hi", "cwd", "."),
            context(Map.of()),
            message -> {
            }
        );
        assertTrue(execution.isError());
        assertEquals("不支持的工具输入字段: cwd。", execution.output());
    }

    @Test
    void shellCwdCapturedFromExecutedCommand() throws Exception {
        ShellEnvironmentHarness harness = testHarness();
        // 用真实 bash 执行，走完整 wrap + cwd 捕获链路
        Executor realExecutor = new cn.lycode.tool.shell.HostExecutor();
        BashTool tool = new BashTool(realExecutor, new RecordingSandboxPolicyResolver(defaultPolicy()), harness);

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd"),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("exitCode=0"), result.output());
        // pwd 未改变目录，无 shellCwd 增量（captured == context.cwd）
        assertFalse(result.output().contains("shellCwd="), result.output());
        assertTrue(harness.snapshotExists(tempDir, "ses_1", "bash"));
    }

    @Test
    void sessionEnvScriptAppliesToWrappedCommand() throws Exception {
        ShellEnvironmentHarness harness = testHarness();
        Path envDir = harness.sessionDir(tempDir, "ses_1").resolve("env");
        Files.createDirectories(envDir);
        Files.writeString(envDir.resolve("01-test.sh"), "export LYCODE_BASH_TOOL_TEST=persisted\n");
        Executor realExecutor = new cn.lycode.tool.shell.HostExecutor();
        BashTool tool = new BashTool(realExecutor, new RecordingSandboxPolicyResolver(defaultPolicy()), harness);

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo \"$LYCODE_BASH_TOOL_TEST\""),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.output().contains("persisted"), result.output());
    }

    @Test
    void mapsAllowedShellToExecutionRequest() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());

        ToolResult<String> shResult = tool.execute(
            Map.of("command", "echo hi", "shell", "sh"),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(shResult.isError(), shResult.output());
        assertEquals("sh", executor.request.get().command().get(0));

        ToolResult<String> zshResult = tool.execute(
            Map.of("command", "echo hi", "shell", "zsh"),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(zshResult.isError());
        assertEquals("zsh", executor.request.get().command().get(0));

    }

    @Test
    void rejectsUnsupportedShell() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        var pythonResult = tool.validateInput(Map.of("command", "echo hi", "shell", "python"), context(Map.of()));
        var relativePathResult = tool.validateInput(Map.of("command", "echo hi", "shell", "bin/bash"), context(Map.of()));
        var absolutePathResult = tool.validateInput(Map.of("command", "echo hi", "shell", "/bin/bash"), context(Map.of()));

        assertFalse(pythonResult.valid());
        assertTrue(pythonResult.messages().getFirst().contains("shell"));
        assertFalse(relativePathResult.valid());
        assertTrue(relativePathResult.messages().getFirst().contains("shell"));
        assertFalse(absolutePathResult.valid());
        assertTrue(absolutePathResult.messages().getFirst().contains("shell"));
    }

    @Test
    void mapsEscalatedSandboxRequestToExecutionRequest() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(
            executor,
            new RecordingSandboxPolicyResolver(defaultPolicy()),
            testHarness()
        );

        ToolResult<String> result = tool.execute(
            Map.of(
                "command", "id",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host access to inspect local process state."
            ),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxPermissions.REQUIRE_ESCALATED, executor.request.get().sandboxPermissions());
        assertEquals(
            Optional.of("Need host access to inspect local process state."),
            executor.request.get().justification()
        );
        assertEquals(Optional.empty(), executor.request.get().additionalPermissions());
        assertEquals(2, executor.requests.size());
        assertTrue(executor.requests.stream().allMatch(request ->
            request.sandboxPermissions() == SandboxPermissions.REQUIRE_ESCALATED
                && request.justification().equals(Optional.of("Need host access to inspect local process state."))
                && request.sandboxPolicy().kind() == SandboxRuntimePolicyKind.DISABLED
        ));
    }

    @Test
    void approvedDefaultRequestUsesHostWithoutResolvingSandboxPolicy() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        FailingSandboxPolicyResolver resolver = new FailingSandboxPolicyResolver();
        BashTool tool = new BashTool(executor, resolver, testHarness());

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd"),
            context(Map.of("permissionApprovedForHostExecution", true)),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, executor.request.get().sandboxPolicy().kind());
        assertEquals(0, resolver.calls.get());
        assertEquals(tempDir.toRealPath(), executor.request.get().cwd());
    }

    @Test
    void approvedAdditionalPermissionsRequestUsesHostWithoutResolvingSandboxPolicy() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        FailingSandboxPolicyResolver resolver = new FailingSandboxPolicyResolver();
        BashTool tool = new BashTool(executor, resolver, testHarness());
        Path cacheDir = Files.createDirectory(tempDir.resolve("host-cache"));
        AdditionalPermissionProfile permissions = additionalWrite(cacheDir);

        ToolResult<String> result = tool.execute(
            Map.of(
                "command", "touch host-cache/out",
                "sandboxPermissions", "withAdditionalPermissions"
            ),
            context(Map.of(
                "additionalPermissions", permissions,
                "approvedAdditionalPermissions", true,
                "permissionApprovedForHostExecution", true
            )),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, executor.request.get().sandboxPolicy().kind());
        assertEquals(Optional.of(permissions), executor.request.get().additionalPermissions());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void approvedEscalatedRequestUsesHostWithoutResolvingSandboxPolicy() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        FailingSandboxPolicyResolver resolver = new FailingSandboxPolicyResolver();
        BashTool tool = new BashTool(executor, resolver, testHarness());

        ToolResult<String> result = tool.execute(
            Map.of(
                "command", "id",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host process access."
            ),
            context(Map.of("permissionApprovedForHostExecution", true)),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, executor.request.get().sandboxPolicy().kind());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void bypassUsesHostWithoutApprovalOrSandboxResolution() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        FailingSandboxPolicyResolver resolver = new FailingSandboxPolicyResolver();
        BashTool tool = new BashTool(executor, resolver, testHarness());

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd"),
            context(Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.BYPASS))),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, executor.request.get().sandboxPolicy().kind());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void mapsApprovedAdditionalPermissionsToSingleExecutionRequest() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());
        Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));
        AdditionalPermissionProfile permissions = additionalWrite(cacheDir);

        ToolResult<String> widenedResult = tool.execute(
            Map.of(
                "command", "touch cache/out",
                "sandboxPermissions", "withAdditionalPermissions"
            ),
            context(Map.of(
                "additionalPermissions", permissions,
                "approvedAdditionalPermissions", true
            )),
            message -> {
            }
        );

        assertFalse(widenedResult.isError());
        assertEquals(SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS, executor.request.get().sandboxPermissions());
        assertEquals(Optional.of(permissions), executor.request.get().additionalPermissions());
        assertEquals(Optional.empty(), executor.request.get().justification());
        assertEquals(2, executor.requests.size());
        assertTrue(executor.requests.stream().allMatch(request ->
            request.sandboxPermissions() == SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS
                && request.additionalPermissions().equals(Optional.of(permissions))
                && request.justification().isEmpty()
        ));

        ToolResult<String> defaultResult = tool.execute(
            Map.of("command", "true"),
            context(Map.of()),
            message -> {
            }
        );

        assertFalse(defaultResult.isError());
        assertEquals(SandboxPermissions.USE_DEFAULT, executor.request.get().sandboxPermissions());
        assertEquals(Optional.empty(), executor.request.get().additionalPermissions());
    }

    @Test
    void rejectsAdditionalPermissionsWithoutApprovedMarker() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());
        Path cacheDir = Files.createDirectory(tempDir.resolve("cache"));

        ToolResult<String> result = tool.execute(
            Map.of(
                "command", "touch cache/out",
                "sandboxPermissions", "withAdditionalPermissions",
                "additionalPermissions", additionalWrite(cacheDir)
            ),
            context(Map.of()),
            message -> {
            }
        );

        assertTrue(result.isError());
        assertEquals(null, executor.request.get());
    }

    @Test
    void rendersSandboxRetryHintFromExecutionMetadata() {
        ExecutionMetadata metadata = ExecutionMetadata.sandboxUnavailable(
            "bubblewrap",
            "bubblewrap unavailable"
        );
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(126, "", "denied", false, Optional.empty(), metadata));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());

        ToolResult<String> result = tool.execute(Map.of("command", "id"), context(Map.of()), message -> {
        });

        assertTrue(result.output().contains("sandboxUnavailable=true"));
        assertTrue(result.output().contains("retryWith=sandboxPermissions=requireEscalated"));
        assertTrue(result.output().contains("retryHint="));
    }

    @Test
    void rendersOrdinarySandboxFailuresWithoutEscalationHints() {
        for (String stderr : List.of(
            "Read-only file system",
            "Permission denied",
            "Network is unreachable"
        )) {
            RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
                1,
                "",
                stderr,
                false,
                Optional.empty(),
                ExecutionMetadata.sandboxed("bubblewrap")
            ));
            BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());

            ToolResult<String> result = tool.execute(Map.of("command", "touch output.txt"), context(Map.of()), message -> {
            });

            assertFalse(result.isError());
            assertTrue(result.output().contains("sandboxed=true"));
            assertTrue(result.output().contains(stderr));
            assertFalse(result.output().contains("sandboxDenied=true"));
            assertFalse(result.output().contains("retryWith="));
            assertFalse(result.output().contains("retryHint="));
        }
    }

    @Test
    void rejectsEscalatedSandboxRequestWithoutJustification() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        var result = tool.validateInput(
            Map.of("command", "id", "sandboxPermissions", "requireEscalated", "justification", " "),
            context(Map.of())
        );

        assertFalse(result.valid());
        assertTrue(result.messages().getFirst().contains("justification"));
    }

    @Test
    void reportsRunningPhaseBeforeExecutingCommand() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()), testHarness());
        List<ToolProgress> progresses = new ArrayList<>();

        tool.execute(Map.of("command", "echo hi"), context(Map.of()), progresses::add);

        ToolProgress first = progresses.getFirst();
        assertEquals(ToolProgressKind.PHASE, first.kind());
        assertEquals("running", first.phase());
        assertEquals("执行 shell 命令", first.title());
    }

    @Test
    void usesDynamicContextCwdAndPassesAbortSignal() throws Exception {
        AbortSignal signal = () -> false;
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        Path nested = Files.createDirectory(tempDir.resolve("nested-cwd"));
        BashTool tool = new BashTool(
            executor,
            new RecordingSandboxPolicyResolver(defaultPolicy()),
            testHarness()
        );

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd"),
            context(tempDir, nested, Map.of("abortSignal", signal)),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertSame(signal, executor.signal.get());
        assertEquals(nested, executor.request.get().cwd());
    }

    @Test
    void rejectsContextCwdOutsideWorkspace() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(defaultPolicy());
        BashTool tool = new BashTool(executor, resolver, testHarness());
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-boundary"));
        Path outsideDir = Files.createDirectory(tempDir.resolve("outside-boundary"));

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd"),
            context(workspace, outsideDir, Map.of()),
            message -> {
            }
        );

        assertTrue(result.isError());
        assertEquals(0, executor.requests.size());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void rejectsContextCwdSymlinkEscape(@TempDir Path outsideDir) throws Exception {
        Path workspace = Files.createDirectory(tempDir.resolve("workspace-symlink"));
        Path escape = Files.createSymbolicLink(workspace.resolve("outside-link"), outsideDir);
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(defaultPolicy());
        BashTool tool = new BashTool(executor, resolver, testHarness());

        ToolResult<String> result = tool.execute(Map.of("command", "pwd"), context(workspace, escape, Map.of()), message -> {
        });

        assertTrue(result.isError());
        assertEquals(0, executor.requests.size());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void isNotReadOnlyAndAllowsManagedSandboxPermission() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));
        Map<String, Object> input = Map.of("command", "echo hi");

        assertFalse(tool.isReadOnly(input));
        assertFalse(tool.isConcurrencySafe(input));
        assertTrue(tool.isDestructive(input));
        assertEquals(PermissionBehavior.ALLOW, tool.checkPermissions(input, context(Map.of())).behavior());
    }

    @Test
    void allowsToolPermissionWhenSandboxAutoAllowIsFailSafe() {
        BashTool tool = new BashTool(
            new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
            new RecordingSandboxPolicyResolver(policy(true, true))
        );

        assertEquals(
            PermissionBehavior.ALLOW,
            tool.checkPermissions(Map.of("command", "echo hi"), context(Map.of())).behavior()
        );
    }

    @Test
    void toolPermissionUsesTheSameChangedRuntimeModeAsExecution() {
        BashTool tool = new BashTool(
            new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
            new PermissionProfileSandboxPolicyResolver(
                PermissionProfiles.workspace(),
                new SandboxPolicyOptions(NetworkMode.DISABLED, true, true),
                false
            )
        );

        PermissionBehavior askBehavior = tool.checkPermissions(
            Map.of("command", "echo hi"),
            context(Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.ASK)))
        ).behavior();
        PermissionBehavior bypassBehavior = tool.checkPermissions(
            Map.of("command", "echo hi"),
            context(Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(PermissionMode.BYPASS)))
        ).behavior();

        assertEquals(PermissionBehavior.ALLOW, askBehavior);
        assertEquals(PermissionBehavior.ASK, bypassBehavior);
    }

    @Test
    void allowsManagedSandboxWhenAutoAllowAndFailIfUnavailableAreFalse() {
        BashTool tool = new BashTool(
            new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
            new RecordingSandboxPolicyResolver(policy(false, false))
        );

        assertEquals(
            PermissionBehavior.ALLOW,
            tool.checkPermissions(Map.of("command", "echo hi"), context(Map.of())).behavior()
        );
    }

    @Test
    void escalatedPermissionCheckAllowsCoordinatorReviewWithoutResolvingSandboxPolicy() {
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(defaultPolicy());
        BashTool tool = new BashTool(
            new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
            resolver
        );

        var decision = tool.checkPermissions(
            Map.of(
                "command", "id",
                "sandboxPermissions", "requireEscalated",
                "justification", "Need host process access."
            ),
            context(Map.of())
        );

        assertEquals(PermissionBehavior.ALLOW, decision.behavior());
        assertEquals(0, resolver.calls.get());
    }

    @Test
    void stillAsksWhenSandboxIsDisabledOrExternal() {
        for (SandboxRuntimePolicyKind kind : List.of(
            SandboxRuntimePolicyKind.DISABLED,
            SandboxRuntimePolicyKind.EXTERNAL
        )) {
            BashTool tool = new BashTool(
                new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
                new RecordingSandboxPolicyResolver(policy(kind, false, false))
            );

            assertEquals(
                PermissionBehavior.ASK,
                tool.checkPermissions(Map.of("command", "echo hi"), context(Map.of())).behavior(),
                kind.name()
            );
        }
    }

    private ToolUseContext context(Map<String, Object> extraMetadata) {
        return context(tempDir, tempDir, extraMetadata);
    }

    private ToolUseContext context(Path workspaceRoot, Path cwd, Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("toolUseId", "toolu_1");
        metadata.putAll(extraMetadata);
        return new ToolUseContext("ses_1", "msg_1", workspaceRoot, cwd, Map.copyOf(metadata));
    }

    private ShellEnvironmentHarness testHarness() {
        return new ShellEnvironmentHarness(tempDir.resolve("shell-state"));
    }

    private SandboxRuntimePolicy defaultPolicy() {
        return policy(false, false);
    }

    private SandboxRuntimePolicy policyForWorkspace(Path workspace) {
        return new SandboxRuntimePolicy(
            List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/lib"), Path.of("/lib64"), Path.of("/etc")),
            List.of(),
            List.of(workspace),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );
    }

    private SandboxRuntimePolicy policy(boolean failIfUnavailable, boolean autoAllowBashIfSandboxed) {
        return policy(SandboxRuntimePolicyKind.MANAGED, failIfUnavailable, autoAllowBashIfSandboxed);
    }

    private SandboxRuntimePolicy policy(
        SandboxRuntimePolicyKind kind,
        boolean failIfUnavailable,
        boolean autoAllowBashIfSandboxed
    ) {
        return new SandboxRuntimePolicy(
            kind,
            List.of(Path.of("/usr")),
            List.of(),
            List.of(tempDir),
            List.of(),
            NetworkMode.DISABLED,
            failIfUnavailable,
            autoAllowBashIfSandboxed
        );
    }

    private AdditionalPermissionProfile additionalWrite(Path path) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(
                    FileSystemPath.exactPath(path.toString()),
                    FileSystemAccessMode.WRITE
                )
            ))),
            Optional.empty()
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final ExecutionResult result;
        private final AtomicReference<ExecutionRequest> request = new AtomicReference<>();
        private final AtomicReference<AbortSignal> signal = new AtomicReference<>();
        private final List<ExecutionRequest> requests = new ArrayList<>();

        private RecordingExecutor(ExecutionResult result) {
            this.result = result;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            this.request.set(request);
            this.signal.set(signal);
            this.requests.add(request);
            progress.progress(ToolProgress.status("executor progress", null));
            return result;
        }
    }

    private static final class RecordingDelegatingExecutor implements Executor {
        private final Executor delegate = new cn.lycode.tool.shell.HostExecutor();
        private final List<ExecutionRequest> requests = new ArrayList<>();
        private final List<AbortSignal> signals = new ArrayList<>();

        @Override
        public String name() {
            return "recording-host";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            requests.add(request);
            signals.add(signal);
            return delegate.execute(request, progress, signal);
        }
    }

    private static final class RecordingSandboxPolicyResolver implements cn.lycode.tool.shell.SandboxPolicyResolver {
        private final SandboxRuntimePolicy policy;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Path> workspace = new AtomicReference<>();
        private final AtomicReference<Path> cwd = new AtomicReference<>();

        private RecordingSandboxPolicyResolver(SandboxRuntimePolicy policy) {
            this.policy = policy;
        }

        @Override
        public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
            calls.incrementAndGet();
            this.workspace.set(workspace);
            this.cwd.set(cwd);
            return policy;
        }
    }

    private static final class FailingSandboxPolicyResolver implements cn.lycode.tool.shell.SandboxPolicyResolver {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
            calls.incrementAndGet();
            throw new IllegalStateException("sandbox policy must not be resolved for host execution");
        }
    }
}
