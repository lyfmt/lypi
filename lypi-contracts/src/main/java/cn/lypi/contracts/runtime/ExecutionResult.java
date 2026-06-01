package cn.lypi.contracts.runtime;

import java.nio.file.Path;
import java.util.Optional;

public record ExecutionResult(
    int exitCode,
    String stdout,
    String stderr,
    boolean timedOut,
    Optional<Path> persistedOutput
) {}

