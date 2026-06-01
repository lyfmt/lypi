package cn.lypi.contracts.runtime;

import java.nio.file.Path;
import java.util.List;

public record SandboxRuntimePolicy(
    List<Path> allowRead,
    List<Path> denyRead,
    List<Path> allowWrite,
    List<Path> denyWrite,
    List<String> allowedDomains,
    List<String> deniedDomains,
    boolean failIfUnavailable,
    boolean autoAllowBashIfSandboxed
) {}

