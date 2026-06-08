package cn.lypi.contracts.runtime;

import java.util.Optional;

/**
 * 描述一次命令执行的运行环境信息。
 */
public record ExecutionMetadata(
    boolean sandboxed,
    String executorName,
    Optional<String> diagnostic
) {
    public ExecutionMetadata {
        executorName = executorName == null ? "" : executorName;
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic;
    }

    /**
     * 返回未指定执行器的非沙盒 metadata。
     */
    public static ExecutionMetadata unspecified() {
        return unsandboxed("");
    }

    /**
     * 返回沙盒执行 metadata。
     */
    public static ExecutionMetadata sandboxed(String executorName) {
        return new ExecutionMetadata(true, executorName, Optional.empty());
    }

    /**
     * 返回非沙盒执行 metadata。
     */
    public static ExecutionMetadata unsandboxed(String executorName) {
        return new ExecutionMetadata(false, executorName, Optional.empty());
    }

    /**
     * 返回带诊断信息的非沙盒执行 metadata。
     */
    public static ExecutionMetadata unsandboxed(String executorName, String diagnostic) {
        return new ExecutionMetadata(false, executorName, Optional.ofNullable(diagnostic));
    }
}
