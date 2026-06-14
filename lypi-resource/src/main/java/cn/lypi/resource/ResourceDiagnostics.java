package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import java.nio.file.Path;
import java.util.Optional;

final class ResourceDiagnostics {
    private ResourceDiagnostics() {
    }

    static ResourceDiagnostic warning(String message, Path path) {
        return new ResourceDiagnostic(ResourceDiagnosticLevel.WARNING, message, Optional.of(path));
    }
}
