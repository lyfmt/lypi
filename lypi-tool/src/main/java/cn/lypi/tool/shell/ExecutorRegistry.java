package cn.lypi.tool.shell;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
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
        if (sandboxEnabled && request != null && request.sandboxPolicy() != null) {
            return sandboxExecutor.execute(request, progress, signal);
        }
        return hostExecutor.execute(request, progress, signal);
    }
}
