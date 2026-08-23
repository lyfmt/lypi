package cn.lycode.tool.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.runtime.ExecutionMetadata;
import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.runtime.SandboxPermissions;
import cn.lycode.contracts.runtime.SandboxRuntimePolicy;
import cn.lycode.contracts.runtime.SandboxRuntimePolicyKind;
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
        assertEquals(SandboxRuntimePolicyKind.MANAGED, sandbox.request.sandboxPolicy().kind());
    }

    @Test
    void failsDefaultExecutionWhenSandboxDisabled() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, false);

        ExecutionResult result = registry.execute(request(policy()), progress -> {
        }, () -> false);

        assertEquals(0, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("executor-registry", result.metadata().executorName());
        assertEquals(true, result.metadata().sandboxUnavailable());
        assertEquals("sandboxPermissions=requireEscalated", result.metadata().retryWith().orElseThrow());
    }

    @Test
    void routesApprovedEscalatedRequestsToHostExecutor() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(escalatedRequest(policy()), progress -> {
        }, () -> false);

        assertEquals(1, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("host", result.metadata().executorName());
        assertEquals(SandboxPermissions.REQUIRE_ESCALATED, host.request.sandboxPermissions());
    }

    @Test
    void routesDisabledProfileRequestsToHostExecutor() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(request(disabledPolicy()), progress -> {
        }, () -> false);

        assertEquals(1, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("host", result.metadata().executorName());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, host.request.sandboxPolicy().kind());
    }

    @Test
    void routesExternalProfileRequestsToHostExecutor() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(request(externalPolicy()), progress -> {
        }, () -> false);

        assertEquals(1, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("host", result.metadata().executorName());
    }

    @Test
    void keepsAdditionalPermissionRequestsInSandboxExecutor() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(additionalPermissionsRequest(policy()), progress -> {
        }, () -> false);

        assertEquals(0, host.calls);
        assertEquals(1, sandbox.calls);
        assertEquals("bubblewrap", result.metadata().executorName());
        assertEquals(SandboxRuntimePolicyKind.MANAGED, sandbox.request.sandboxPolicy().kind());
    }

    @Test
    void disabledPolicyRoutesAdditionalPermissionRequestToHost() {
        RecordingExecutor host = new RecordingExecutor("host");
        RecordingExecutor sandbox = new RecordingExecutor("bubblewrap");
        ExecutorRegistry registry = new ExecutorRegistry(host, sandbox, true);

        ExecutionResult result = registry.execute(additionalPermissionsRequest(disabledPolicy()), progress -> {
        }, () -> false);

        assertEquals(1, host.calls);
        assertEquals(0, sandbox.calls);
        assertEquals("host", result.metadata().executorName());
        assertEquals(SandboxRuntimePolicyKind.DISABLED, host.request.sandboxPolicy().kind());
        assertEquals(SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS, host.request.sandboxPermissions());
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

    private ExecutionRequest escalatedRequest(SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", "true"),
            Path.of("."),
            Map.of(),
            Duration.ofSeconds(1),
            policy,
            SandboxPermissions.REQUIRE_ESCALATED,
            Optional.of("Need host execution.")
        );
    }

    private ExecutionRequest additionalPermissionsRequest(SandboxRuntimePolicy policy) {
        return new ExecutionRequest(
            List.of("bash", "-lc", "true"),
            Path.of("."),
            Map.of(),
            Duration.ofSeconds(1),
            policy,
            SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS,
            Optional.empty()
        );
    }

    private SandboxRuntimePolicy policy() {
        return new SandboxRuntimePolicy(List.of(), List.of(), List.of(Path.of(".")), List.of(), NetworkMode.DISABLED, false, false);
    }

    private SandboxRuntimePolicy disabledPolicy() {
        return new SandboxRuntimePolicy(
            SandboxRuntimePolicyKind.DISABLED,
            List.of(Path.of("/")),
            List.of(),
            List.of(Path.of("/")),
            List.of(),
            NetworkMode.HOST,
            false,
            true
        );
    }

    private SandboxRuntimePolicy externalPolicy() {
        return new SandboxRuntimePolicy(
            SandboxRuntimePolicyKind.EXTERNAL,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            NetworkMode.HOST,
            false,
            true
        );
    }

    private static final class RecordingExecutor implements Executor {
        private final String name;
        private int calls;
        private ExecutionRequest request;

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
            this.request = request;
            return new ExecutionResult(0, "", "", false, Optional.empty(), ExecutionMetadata.unsandboxed(name));
        }
    }
}
