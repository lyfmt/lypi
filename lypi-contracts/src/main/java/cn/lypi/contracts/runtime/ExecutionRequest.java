package cn.lypi.contracts.runtime;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ExecutionRequest(
    List<String> command,
    Path cwd,
    Map<String, String> env,
    Duration timeout,
    SandboxRuntimePolicy sandboxPolicy,
    SandboxPermissions sandboxPermissions,
    Optional<AdditionalPermissionProfile> additionalPermissions,
    Optional<String> justification
) {
    public ExecutionRequest(
        List<String> command,
        Path cwd,
        Map<String, String> env,
        Duration timeout,
        SandboxRuntimePolicy sandboxPolicy
    ) {
        this(command, cwd, env, timeout, sandboxPolicy, SandboxPermissions.USE_DEFAULT, Optional.empty());
    }

    public ExecutionRequest(
        List<String> command,
        Path cwd,
        Map<String, String> env,
        Duration timeout,
        SandboxRuntimePolicy sandboxPolicy,
        SandboxPermissions sandboxPermissions,
        Optional<String> justification
    ) {
        this(command, cwd, env, timeout, sandboxPolicy, sandboxPermissions, Optional.empty(), justification);
    }

    public ExecutionRequest {
        command = command == null ? List.of() : List.copyOf(command);
        env = env == null ? Map.of() : Map.copyOf(env);
        sandboxPermissions = sandboxPermissions == null ? SandboxPermissions.USE_DEFAULT : sandboxPermissions;
        additionalPermissions = additionalPermissions == null ? Optional.empty() : additionalPermissions;
        justification = justification == null ? Optional.empty() : justification.filter(value -> !value.isBlank());
    }
}
