package cn.lypi.contracts.runtime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public record ExecutionRequest(
    List<String> command,
    Path cwd,
    Map<String, String> env,
    Duration timeout,
    SandboxRuntimePolicy sandboxPolicy
) {}

