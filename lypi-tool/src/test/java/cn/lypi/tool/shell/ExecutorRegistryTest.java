package cn.lypi.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExecutorRegistryTest {
    @Test
    void routesSandboxRequestsToSandboxExecutorWhenEnabled() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(request(policy()), progress -> {
        }, () -> false);

        assertEquals("executor-registry", registry.name());
        assertEquals(0, host.calls);
        assertEquals(1, sandbox.calls);
        assertEquals("bubblewrap", result.metadata().executorName());
    }

    @Test
    void routesToHostExecutorWhenSandboxDisabled() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, false);

        ExecutionResult result = registry.execute(request(policy()), progress -> {
        }, () -> false);

        assertEquals(1, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("host", result.metadata().executorName());
    }

    private ExecutionRequest request(SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", "true"),
            Path.of("."),
            Map.of(),
            Duration.ofSeconds(1),
            policy
        );
    }

    private SandboxRuntimePolicy policy() {
        return new SandboxRuntimePolicy(List.of(), List.of(), List.of(Path.of(".")), List.of(), NetworkMode.DISABLED, false, false);
    }

    private static final class RecordingExecutor implements Executor {
        private final String name;
        private int calls;

        private RecordingExecutor(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            calls++;
            return new ExecutionResult(0, "", "", false, Optional.empty(), ExecutionMetadata.unsandboxed(name));
        }
    }
}
