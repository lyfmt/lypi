package cn.lycode.tool.shell;

import cn.lycode.contracts.common.AbortSignal;
import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.runtime.ExecutionMetadata;
import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.SandboxPermissions;
import cn.lycode.contracts.runtime.SandboxRuntimePolicyKind;
import java.util.Optional;
import java.util.Objects;

/**
 * 根据沙盒开关选择命令执行器。
 */
public final class ExecutorRegistry implements Executor {
    private final Executor hostExecutor;
    private final Executor sandboxExecutor;
    private final boolean sandboxEnabled;

    public ExecutorRegistry(Executor hostExecutor, Executor sandboxExecutor, boolean sandboxEnabled) {
        this.hostExecutor = Objects.requireNonNull(hostExecutor, "hostExecutor must not be null");
        this.sandboxExecutor = Objects.requireNonNull(sandboxExecutor, "sandboxExecutor must not be null");
        this.sandboxEnabled = sandboxEnabled;
    }

    @Override
    public String name() {
        return "executor-registry";
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
        if (request != null && routesToHost(request)) {
            return hostExecutor.execute(request, progress, signal);
        }
        if (sandboxEnabled && request != null && request.sandboxPolicy() != null) {
            return sandboxExecutor.execute(request, progress, signal);
        }
        return new ExecutionResult(
            126,
            "",
            "sandbox unavailable: default execution requires sandbox",
            false,
            Optional.empty(),
            ExecutionMetadata.sandboxUnavailable(name(), "default execution requires sandbox")
        );
    }

    private boolean routesToHost(ExecutionRequest request) {
        if (request.sandboxPermissions() == SandboxPermissions.REQUIRE_ESCALATED) {
            return true;
        }
        return request.sandboxPolicy() != null
            && (request.sandboxPolicy().kind() == SandboxRuntimePolicyKind.DISABLED
                || request.sandboxPolicy().kind() == SandboxRuntimePolicyKind.EXTERNAL);
    }
}
