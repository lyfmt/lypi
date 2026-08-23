package cn.lycode.resource;

import cn.lycode.contracts.resource.ResourceDiagnostic;
import cn.lycode.contracts.resource.ResourceDiagnosticLevel;
import java.nio.file.Path;
import java.util.Optional;

final class ResourceDiagnostics {
    private ResourceDiagnostics() {
    }

    static ResourceDiagnostic warning(String message, Path path) {
        return new ResourceDiagnostic(ResourceDiagnosticLevel.WARNING, message, Optional.of(path));
    }
}
