package cn.lypi.resource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class ProjectRootResolver {
    ResourceDiscoveryPlan resolve(Path cwd) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        Path start = Files.isRegularFile(normalizedCwd) ? normalizedCwd.getParent() : normalizedCwd;
        Path current = start;
        while (current != null) {
            if (Files.exists(current.resolve(".git"))) {
                return new ResourceDiscoveryPlan(current, start, List.of(), List.of());
            }
            current = current.getParent();
        }
        return new ResourceDiscoveryPlan(start, start, List.of(), List.of());
    }
}
