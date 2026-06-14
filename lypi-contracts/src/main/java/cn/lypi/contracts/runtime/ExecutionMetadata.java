package cn.lypi.contracts.runtime;

import java.util.Optional;

/**
 * 描述一次命令执行的运行环境信息。
 */
public record ExecutionMetadata(
    boolean sandboxed,
    String executorName,
    Optional<String> diagnostic,
    boolean sandboxDenied,
    boolean sandboxUnavailable,
    Optional<String> retryWith,
    Optional<String> retryHint
) {
    private static final String SANDBOX_ESCALATION_RETRY_WITH = "sandboxPermissions=requireEscalated";
    private static final String SANDBOX_ESCALATION_RETRY_HINT =
        "Retry with sandboxPermissions=requireEscalated and provide a user-facing justification.";

    public ExecutionMetadata(boolean sandboxed, String executorName, Optional<String> diagnostic) {
        this(sandboxed, executorName, diagnostic, false, false, Optional.empty(), Optional.empty());
    }

    public ExecutionMetadata {
        executorName = executorName == null ? "" : executorName;
        diagnostic = diagnostic == null ? Optional.empty() : diagnostic;
        retryWith = retryWith == null ? Optional.empty() : retryWith;
        retryHint = retryHint == null ? Optional.empty() : retryHint;
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

    /**
     * 返回沙箱不可用失败 metadata，并附带模型可读重试提示。
     */
    public static ExecutionMetadata sandboxUnavailable(String executorName, String diagnostic) {
        return sandboxFailure(executorName, diagnostic, false, true);
    }

    /**
     * 返回沙箱拒绝失败 metadata，并附带模型可读重试提示。
     */
    public static ExecutionMetadata sandboxDenied(String executorName, String diagnostic) {
        return sandboxFailure(executorName, diagnostic, true, false);
    }

    private static ExecutionMetadata sandboxFailure(
        String executorName,
        String diagnostic,
        boolean sandboxDenied,
        boolean sandboxUnavailable
    ) {
        return new ExecutionMetadata(
            false,
            executorName,
            Optional.ofNullable(diagnostic),
            sandboxDenied,
            sandboxUnavailable,
            Optional.of(SANDBOX_ESCALATION_RETRY_WITH),
            Optional.of(SANDBOX_ESCALATION_RETRY_HINT)
        );
    }
}
