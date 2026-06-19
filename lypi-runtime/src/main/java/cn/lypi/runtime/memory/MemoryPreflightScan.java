package cn.lypi.runtime.memory;

import java.nio.file.Path;
import java.util.List;

/**
 * 后台沉淀前的 memory 结构扫描摘要。
 */
public record MemoryPreflightScan(
    List<Path> manifestPaths,
    List<Path> memoryPaths,
    List<MemoryLintDiagnostic> diagnostics
) {
    public MemoryPreflightScan {
        manifestPaths = List.copyOf(manifestPaths == null ? List.of() : manifestPaths);
        memoryPaths = List.copyOf(memoryPaths == null ? List.of() : memoryPaths);
        diagnostics = List.copyOf(diagnostics == null ? List.of() : diagnostics);
    }
}
