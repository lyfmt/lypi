package cn.lypi.resource;

import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class MemorySourceScanner {
    List<MemorySource> scan(List<ResourceLocation> locations, List<ResourceDiagnostic> diagnostics) {
        List<MemorySource> sources = new ArrayList<>();
        Set<Path> seenRealPaths = new HashSet<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            scanRootMemory(location, diagnostics, sources, seenRealPaths);
            scanMemoryDirectory(location, diagnostics, sources, seenRealPaths);
        }
        return List.copyOf(sources);
    }

    private List<ResourceLocation> orderedLocations(List<ResourceLocation> locations) {
        return locations.stream()
            .filter(location -> switch (location.layer()) {
                case USER, PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> true;
                case PLATFORM, SESSION, MCP_DERIVED -> false;
            })
            .sorted(Comparator.comparingInt(ResourceLocation::priority))
            .toList();
    }

    private void scanRootMemory(
        ResourceLocation location,
        List<ResourceDiagnostic> diagnostics,
        List<MemorySource> sources,
        Set<Path> seenRealPaths
    ) {
        Path file = switch (location.layer()) {
            case USER -> location.root().resolve("memory.md");
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> location.root().resolve("MEMORY.md");
            default -> null;
        };
        if (file != null && Files.isRegularFile(file)) {
            addMemorySource(memoryScope(location), file, diagnostics, sources, seenRealPaths);
        }
    }

    private void scanMemoryDirectory(
        ResourceLocation location,
        List<ResourceDiagnostic> diagnostics,
        List<MemorySource> sources,
        Set<Path> seenRealPaths
    ) {
        Path directory = switch (location.layer()) {
            case USER -> location.root().resolve("memory");
            case PROJECT, NESTED_PROJECT, EXPLICIT_PATH -> location.root().resolve(".ly-pi").resolve("memory");
            default -> null;
        };
        if (directory != null && Files.isDirectory(directory)) {
            for (Path file : ResourceFiles.regularFiles(directory, diagnostics)) {
                addMemorySource(memoryScope(location), file, diagnostics, sources, seenRealPaths);
            }
        }
    }

    private void addMemorySource(
        MemoryScope scope,
        Path file,
        List<ResourceDiagnostic> diagnostics,
        List<MemorySource> sources,
        Set<Path> seenRealPaths
    ) {
        Path realPath = realPath(file, diagnostics);
        if (realPath != null && !seenRealPaths.add(realPath)) {
            diagnostics.add(ResourceDiagnostics.warning("duplicate memory source: " + realPath, file));
            return;
        }
        ResourceFiles.readString(file, diagnostics).ifPresent(content ->
            sources.add(new MemorySource(scope, file, content, Hashing.sha256(content)))
        );
    }

    private MemoryScope memoryScope(ResourceLocation location) {
        return location.layer() == ResourceLayer.USER ? MemoryScope.USER : MemoryScope.PROJECT;
    }

    private Path realPath(Path file, List<ResourceDiagnostic> diagnostics) {
        try {
            return file.toRealPath();
        } catch (Exception exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to resolve memory source realpath: " + exception.getMessage(), file));
            return null;
        }
    }
}
