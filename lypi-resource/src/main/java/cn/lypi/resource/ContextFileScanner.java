package cn.lypi.resource;

import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

class ContextFileScanner {
    private static final List<String> SYSTEM_FILES = List.of("SYSTEM.md", "APPEND_SYSTEM.md");
    private static final List<String> INSTRUCTION_FILES = List.of("AGENTS.md", "CLAUDE.md");

    List<ContextFile> scan(List<ResourceLocation> locations, List<ResourceDiagnostic> diagnostics) {
        List<ContextFile> files = new ArrayList<>();
        for (ResourceLocation location : orderedLocations(locations)) {
            loadByName(location, SYSTEM_FILES, diagnostics, files);
            loadByName(location, INSTRUCTION_FILES, diagnostics, files);
        }
        return List.copyOf(files);
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

    private void loadByName(
        ResourceLocation location,
        List<String> fileNames,
        List<ResourceDiagnostic> diagnostics,
        List<ContextFile> files
    ) {
        for (String fileName : fileNames) {
            Path file = location.root().resolve(fileName);
            if (Files.isRegularFile(file)) {
                ResourceFiles.readString(file, diagnostics).ifPresent(content ->
                    files.add(new ContextFile(file, content, Hashing.sha256(content)))
                );
            }
        }
    }
}
