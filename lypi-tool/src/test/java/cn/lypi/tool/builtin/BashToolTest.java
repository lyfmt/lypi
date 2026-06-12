package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        assertEquals(List.of("useDefault", "requireEscalated"), sandboxPermissions.get("enum"));
        assertEquals(Map.of("type", "string"), properties.get("justification"));
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
    void mapsCommandToExecutionRequestAndResult() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(7, "out", "err", false, Optional.empty()));
        RecordingSandboxPolicyResolver resolver = new RecordingSandboxPolicyResolver(defaultPolicy());
        BashTool tool = new BashTool(executor, resolver);
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo hi", "timeoutSeconds", 3),
            context(Map.of()),
            progresses::add
        );

        assertFalse(result.isError());
        assertEquals(List.of("bash", "-lc", "echo hi"), executor.request.get().command());
        assertEquals(tempDir, executor.request.get().cwd());
        assertEquals(Duration.ofSeconds(3), executor.request.get().timeout());
        assertSame(resolver.policy, executor.request.get().sandboxPolicy());
        assertEquals(SandboxPermissions.USE_DEFAULT, executor.request.get().sandboxPermissions());
        assertEquals(Optional.empty(), executor.request.get().justification());
        assertEquals(tempDir, resolver.workspace.get());
        assertEquals(tempDir, resolver.cwd.get());
        assertEquals(NetworkMode.DISABLED, executor.request.get().sandboxPolicy().networkMode());
        assertFalse(executor.request.get().sandboxPolicy().failIfUnavailable());
        assertFalse(executor.request.get().sandboxPolicy().autoAllowBashIfSandboxed());
        assertTrue(result.output().contains("exitCode=7"));
        assertTrue(result.output().contains("stdout:\nout"));
        assertTrue(result.output().contains("stderr:\nerr"));
        assertEquals(List.of(
            ToolProgress.phase("running", "执行 shell 命令"),
            ToolProgress.status("executor progress", null)
        ), progresses);
    }

    @Test
    void mapsEscalatedSandboxRequestToExecutionRequest() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()));

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
    }

    @Test
    void rendersSandboxRetryHintFromExecutionMetadata() {
        ExecutionMetadata metadata = ExecutionMetadata.sandboxUnavailable(
            "bubblewrap",
            "bubblewrap unavailable"
        );
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(126, "", "denied", false, Optional.empty(), metadata));
        BashTool tool = new BashTool(executor, new RecordingSandboxPolicyResolver(defaultPolicy()));

        ToolResult<String> result = tool.execute(Map.of("command", "id"), context(Map.of()), message -> {
        });

        assertTrue(result.output().contains("sandboxUnavailable=true"));
        assertTrue(result.output().contains("retryWith=sandboxPermissions=requireEscalated"));
        assertTrue(result.output().contains("retryHint="));
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
        BashTool tool = new BashTool(executor);
        List<ToolProgress> progresses = new ArrayList<>();

        tool.execute(Map.of("command", "echo hi"), context(Map.of()), progresses::add);

        ToolProgress first = progresses.getFirst();
        assertEquals(ToolProgressKind.PHASE, first.kind());
        assertEquals("running", first.phase());
        assertEquals("执行 shell 命令", first.title());
    }

    @Test
    void supportsCwdOverrideInsideWorkspaceAndPassesAbortSignal() {
        AbortSignal signal = () -> true;
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor);

        ToolResult<String> result = tool.execute(
            Map.of("command", "pwd", "cwd", "."),
            context(Map.of("abortSignal", signal)),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertSame(signal, executor.signal.get());
        assertEquals(tempDir, executor.request.get().cwd());
    }

    @Test
    void rejectsCwdOutsideWorkspace() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        ToolResult<String> result = tool.execute(Map.of("command", "pwd", "cwd", "../"), context(Map.of()), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    @Test
    void rejectsCwdSymlinkOutsideWorkspace(@TempDir Path outsideDir) throws Exception {
        Files.createSymbolicLink(tempDir.resolve("outside-link"), outsideDir);
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        BashTool tool = new BashTool(executor);

        ToolResult<String> result = tool.execute(Map.of("command", "pwd", "cwd", "outside-link"), context(Map.of()), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
        assertEquals(null, executor.request.get());
    }

    @Test
    void isNotReadOnlyAndAsksPermission() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));
        Map<String, Object> input = Map.of("command", "echo hi");

        assertFalse(tool.isReadOnly(input));
        assertFalse(tool.isConcurrencySafe(input));
        assertTrue(tool.isDestructive(input));
        assertEquals(PermissionBehavior.ASK, tool.checkPermissions(input, context(Map.of())).behavior());
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
    void stillAsksWhenSandboxAutoAllowCanFallbackToHost() {
        BashTool tool = new BashTool(
            new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())),
            new RecordingSandboxPolicyResolver(policy(false, true))
        );

        assertEquals(
            PermissionBehavior.ASK,
            tool.checkPermissions(Map.of("command", "echo hi"), context(Map.of())).behavior()
        );
    }

    private ToolUseContext context(Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("toolUseId", "toolu_1");
        metadata.putAll(extraMetadata);
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.copyOf(metadata));
    }

    private SandboxRuntimePolicy defaultPolicy() {
        return policy(false, false);
    }

    private SandboxRuntimePolicy policy(boolean failIfUnavailable, boolean autoAllowBashIfSandboxed) {
        return new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(),
            List.of(tempDir),
            List.of(),
            NetworkMode.DISABLED,
            failIfUnavailable,
            autoAllowBashIfSandboxed
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final ExecutionResult result;
        private final AtomicReference<ExecutionRequest> request = new AtomicReference<>();
        private final AtomicReference<AbortSignal> signal = new AtomicReference<>();

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
            progress.progress(ToolProgress.status("executor progress", null));
            return result;
        }
    }

    private static final class RecordingSandboxPolicyResolver implements cn.lypi.tool.shell.SandboxPolicyResolver {
        private final SandboxRuntimePolicy policy;
        private final AtomicReference<Path> workspace = new AtomicReference<>();
        private final AtomicReference<Path> cwd = new AtomicReference<>();

        private RecordingSandboxPolicyResolver(SandboxRuntimePolicy policy) {
            this.policy = policy;
        }

        @Override
        public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
            this.workspace.set(workspace);
            this.cwd.set(cwd);
            return policy;
        }
    }
}
