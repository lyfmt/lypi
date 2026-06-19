package cn.lypi.security;

import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.PermissionProfile;
import cn.lypi.contracts.security.PermissionProfileConfig;
import cn.lypi.contracts.security.PermissionProfileSelection;
import cn.lypi.contracts.security.PermissionProfiles;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 编译 Codex 风格权限 profile 配置。
 */
public final class PermissionProfileConfigCompiler {
    /**
     * 编译 active profile。
     */
    public PermissionProfileSelection compile(Map<String, PermissionProfileConfig> profiles, String activeProfileId) {
        Map<String, PermissionProfileConfig> safeProfiles = profiles == null ? Map.of() : Map.copyOf(profiles);
        validateCustomProfileIds(safeProfiles);
        String profileId = requireProfileId(activeProfileId);
        CompiledProfile compiled = compileProfile(safeProfiles, profileId, new LinkedHashSet<>());
        return new PermissionProfileSelection(
            new ActivePermissionProfile(profileId, compiled.extendsProfile()),
            compiled.permissionProfile()
        );
    }

    private void validateCustomProfileIds(Map<String, PermissionProfileConfig> profiles) {
        for (String id : profiles.keySet()) {
            String safeId = requireProfileId(id);
            if (safeId.startsWith(":")) {
                throw new IllegalArgumentException("Custom permission profile id must not start with ':'");
            }
        }
    }

    private CompiledProfile compileProfile(
        Map<String, PermissionProfileConfig> profiles,
        String profileId,
        Set<String> stack
    ) {
        if (profileId.startsWith(":")) {
            return new CompiledProfile(Optional.empty(), builtInProfile(profileId));
        }
        if (!stack.add(profileId)) {
            throw new IllegalArgumentException("Permission profile extends cycle: " + String.join(" -> ", stack) + " -> " + profileId);
        }
        PermissionProfileConfig config = profiles.get(profileId);
        if (config == null) {
            throw new IllegalArgumentException("Unknown permission profile: " + profileId);
        }
        Optional<String> parentId = config.extendsProfile();
        PermissionProfile baseProfile = parentId
            .map(parent -> compileParentProfile(profiles, parent, stack))
            .orElseGet(PermissionProfiles::readOnly);
        stack.remove(profileId);
        return new CompiledProfile(parentId, applyOverrides(baseProfile, config));
    }

    private PermissionProfile compileParentProfile(
        Map<String, PermissionProfileConfig> profiles,
        String parentId,
        Set<String> stack
    ) {
        String safeParentId = requireProfileId(parentId);
        if (safeParentId.startsWith(":")) {
            return inheritableBuiltInProfile(safeParentId);
        }
        return compileProfile(profiles, safeParentId, stack).permissionProfile();
    }

    private PermissionProfile applyOverrides(PermissionProfile baseProfile, PermissionProfileConfig config) {
        config.fileSystem().ifPresent(this::validateCustomFilesystemOverride);
        FileSystemPermissionPolicy fileSystem = applyWorkspaceRoots(
            config.fileSystem().orElseGet(baseProfile::fileSystem),
            config.workspaceRoots()
        );
        NetworkPermissionPolicy network = config.network().orElseGet(baseProfile::network);
        return switch (baseProfile.kind()) {
            case MANAGED -> new ManagedPermissionProfile(fileSystem, network);
            case DISABLED -> config.fileSystem().isPresent() || config.network().isPresent()
                ? new ManagedPermissionProfile(fileSystem, network)
                : baseProfile;
            case EXTERNAL -> config.fileSystem().isPresent()
                ? new ManagedPermissionProfile(fileSystem, network)
                : PermissionProfiles.external(network);
        };
    }

    private void validateCustomFilesystemOverride(FileSystemPermissionPolicy fileSystem) {
        if (fileSystem.kind() != FileSystemPolicyKind.RESTRICTED) {
            throw new IllegalArgumentException("Custom permission profile filesystem policy must be restricted");
        }
    }

    private FileSystemPermissionPolicy applyWorkspaceRoots(
        FileSystemPermissionPolicy fileSystem,
        List<Path> workspaceRoots
    ) {
        if (workspaceRoots.isEmpty() || fileSystem.kind() != FileSystemPolicyKind.RESTRICTED) {
            return fileSystem;
        }
        List<FileSystemPermissionEntry> entries = new ArrayList<>(fileSystem.entries());
        workspaceRoots.stream()
            .map(Path::toString)
            .map(FileSystemPath::exactPath)
            .map(path -> new FileSystemPermissionEntry(path, FileSystemAccessMode.WRITE))
            .forEach(entries::add);
        return FileSystemPermissionPolicy.restricted(entries);
    }

    private PermissionProfile builtInProfile(String profileId) {
        return switch (profileId) {
            case ":read-only" -> PermissionProfiles.readOnly();
            case ":workspace" -> PermissionProfiles.workspace();
            case ":danger-full-access" -> PermissionProfiles.dangerFullAccess();
            case ":external" -> PermissionProfiles.external(NetworkPermissionPolicy.restricted());
            default -> throw new IllegalArgumentException("Unknown built-in permission profile: " + profileId);
        };
    }

    private PermissionProfile inheritableBuiltInProfile(String profileId) {
        return switch (profileId) {
            case ":read-only" -> PermissionProfiles.readOnly();
            case ":workspace" -> PermissionProfiles.workspace();
            case ":danger-full-access", ":external" ->
                throw new IllegalArgumentException("Unsupported parent built-in permission profile: " + profileId);
            default -> throw new IllegalArgumentException("Unknown built-in permission profile: " + profileId);
        };
    }

    private String requireProfileId(String profileId) {
        Objects.requireNonNull(profileId, "profileId");
        if (profileId.isBlank()) {
            throw new IllegalArgumentException("profileId must not be blank");
        }
        return profileId;
    }

    private record CompiledProfile(
        Optional<String> extendsProfile,
        PermissionProfile permissionProfile
    ) {
        private CompiledProfile {
            extendsProfile = extendsProfile == null ? Optional.empty() : extendsProfile;
            permissionProfile = Objects.requireNonNull(permissionProfile, "permissionProfile");
        }
    }
}
