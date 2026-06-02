package cn.lypi.resource;

import cn.lypi.contracts.resource.ResourceDiagnostic;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

final class ResourceFiles {
    private ResourceFiles() {
    }

    static Optional<String> readString(Path file, List<ResourceDiagnostic> diagnostics) {
        try {
            return Optional.of(Files.readString(file));
        } catch (IOException exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to read resource file: " + exception.getMessage(), file));
            return Optional.empty();
        }
    }

    static List<Path> regularFiles(Path root, List<ResourceDiagnostic> diagnostics) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
        } catch (IOException exception) {
            diagnostics.add(ResourceDiagnostics.warning("Failed to scan resource directory: " + exception.getMessage(), root));
            return List.of();
        }
    }
}
