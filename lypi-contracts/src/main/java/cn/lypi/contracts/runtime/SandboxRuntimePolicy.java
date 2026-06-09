package cn.lypi.contracts.runtime;

import java.nio.file.Path;
import java.util.List;

public record SandboxRuntimePolicy(
    List<Path> allowRead,
    List<Path> denyRead,
    List<Path> allowWrite,
    List<Path> denyWrite,
    NetworkMode networkMode,
    boolean failIfUnavailable,
    boolean autoAllowBashIfSandboxed
) {
    public SandboxRuntimePolicy {
        allowRead = allowRead == null ? List.of() : List.copyOf(allowRead);
        denyRead = denyRead == null ? List.of() : List.copyOf(denyRead);
        allowWrite = allowWrite == null ? List.of() : List.copyOf(allowWrite);
        denyWrite = denyWrite == null ? List.of() : List.copyOf(denyWrite);
        networkMode = networkMode == null ? NetworkMode.DISABLED : networkMode;
    }
}
