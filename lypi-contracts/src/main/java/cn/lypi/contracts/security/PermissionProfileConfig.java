package cn.lypi.contracts.security;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 表示用户可配置的权限 profile 片段。
 */
public record PermissionProfileConfig(
    String description,
    Optional<String> extendsProfile,
    List<Path> workspaceRoots,
    Optional<FileSystemPermissionPolicy> fileSystem,
    Optional<NetworkPermissionPolicy> network
) {
    public PermissionProfileConfig {
        description = description == null ? "" : description;
        extendsProfile = extendsProfile == null ? Optional.empty() : extendsProfile;
        List<Path> safeWorkspaceRoots = workspaceRoots == null ? List.of() : workspaceRoots;
        fileSystem = fileSystem == null ? Optional.empty() : fileSystem;
        network = network == null ? Optional.empty() : network;
        extendsProfile.ifPresent(parent -> {
            if (parent.isBlank()) {
                throw new IllegalArgumentException("extendsProfile must not be blank");
            }
        });
        safeWorkspaceRoots.forEach(root -> {
            if (root == null || root.toString().isBlank()) {
                throw new IllegalArgumentException("workspace root must not be blank");
            }
        });
        workspaceRoots = List.copyOf(safeWorkspaceRoots);
    }
}
