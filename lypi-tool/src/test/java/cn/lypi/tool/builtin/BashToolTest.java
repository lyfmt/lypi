package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
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
    void mapsCommandToExecutionRequestAndResult() {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(7, "out", "err", false, Optional.empty()));
        BashTool tool = new BashTool(executor);
        List<String> progressMessages = new ArrayList<>();

        ToolResult<String> result = tool.execute(
            Map.of("command", "echo hi", "timeoutSeconds", 3),
            context(Map.of()),
            progressMessages::add
        );

        assertFalse(result.isError());
        assertEquals(List.of("bash", "-lc", "echo hi"), executor.request.get().command());
        assertEquals(tempDir, executor.request.get().cwd());
        assertEquals(Duration.ofSeconds(3), executor.request.get().timeout());
        assertTrue(result.output().contains("exitCode=7"));
        assertTrue(result.output().contains("stdout:\nout"));
        assertTrue(result.output().contains("stderr:\nerr"));
        assertEquals(List.of("executor progress"), progressMessages);
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
    void isNotReadOnlyAndAsksPermission() {
        BashTool tool = new BashTool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));
        Map<String, Object> input = Map.of("command", "echo hi");

        assertFalse(tool.isReadOnly(input));
        assertFalse(tool.isConcurrencySafe(input));
        assertTrue(tool.isDestructive(input));
        assertEquals(PermissionBehavior.ASK, tool.checkPermissions(input, context(Map.of())).behavior());
    }

    private ToolUseContext context(Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("toolUseId", "toolu_1");
        metadata.putAll(extraMetadata);
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.copyOf(metadata));
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
            progress.progress("executor progress");
            return result;
        }
    }
}
