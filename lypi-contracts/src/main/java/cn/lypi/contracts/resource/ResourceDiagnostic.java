package cn.lypi.contracts.resource;

import java.nio.file.Path;
import java.util.Optional;

public record ResourceDiagnostic(
    ResourceDiagnosticLevel level,
    String message,
    Optional<Path> path
) {}

