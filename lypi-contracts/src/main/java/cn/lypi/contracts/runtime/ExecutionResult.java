package cn.lypi.contracts.runtime;

import java.nio.file.Path;
import java.util.Optional;

public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    boolean timedOut,
    Optional<Path> persistedOutput,
    ExecutionMetadata metadata
) {
    public ExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut,
        Optional<Path> persistedOutput
    ) {
        this(exitCode, stdout, stderr, timedOut, persistedOutput, ExecutionMetadata.unspecified());
    }

    public ExecutionResult {
        stdout = stdout == null ? "" : stdout;
        stderr = stderr == null ? "" : stderr;
        persistedOutput = persistedOutput == null ? Optional.empty() : persistedOutput;
        metadata = metadata == null ? ExecutionMetadata.unspecified() : metadata;
    }

    /**
     * 返回替换 metadata 后的新执行结果。
     */
    public ExecutionResult withMetadata(ExecutionMetadata metadata) {
        return new ExecutionResult(exitCode, stdout, stderr, timedOut, persistedOutput, metadata);
    }
}
