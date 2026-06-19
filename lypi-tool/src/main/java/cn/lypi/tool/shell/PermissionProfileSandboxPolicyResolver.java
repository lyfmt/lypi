package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.runtime.SandboxRuntimePolicyKind;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.DisabledPermissionProfile;
import cn.lypi.contracts.security.ExternalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionProfile;
import cn.lypi.contracts.security.PermissionProfiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 将 Codex 风格 permission profile 投影为 ly-pi 命令沙盒运行时策略。
 */
public final class PermissionProfileSandboxPolicyResolver implements SandboxPolicyResolver {
    private final PermissionProfile permissionProfile;
    private final SandboxPolicyOptions options;

    public PermissionProfileSandboxPolicyResolver(PermissionProfile permissionProfile, SandboxPolicyOptions options) {
        this.permissionProfile = permissionProfile == null ? PermissionProfiles.workspace() : permissionProfile;
        this.options = options == null ? SandboxPolicyOptions.defaults() : options;
    }

    @Override
    public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
        return resolve(workspace, cwd, AdditionalPermissionProfile.empty());
    }

    /**
     * 生成携带单次额外权限的沙盒策略。
     *
     * additionalPermissions 只影响本次 resolve 调用，不写入 resolver 状态。
     */
    @Override
    public SandboxRuntimePolicy resolve(Path workspace, Path cwd, AdditionalPermissionProfile additionalPermissions) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        Path realWorkspace = realPath(workspace, "workspace");
        realPath(cwd, "cwd");
        AdditionalPermissionProfile safeAdditionalPermissions = additionalPermissions == null
            ? AdditionalPermissionProfile.empty()
            : additionalPermissions;
        return switch (permissionProfile) {
            case ManagedPermissionProfile managed -> managedPolicy(realWorkspace, managed, safeAdditionalPermissions);
            case DisabledPermissionProfile ignored -> new SandboxRuntimePolicy(
                SandboxRuntimePolicyKind.DISABLED,
                List.of(Path.of("/")),
                List.of(),
                List.of(Path.of("/")),
                List.of(),
                NetworkMode.HOST,
                false,
                true
            );
            case ExternalPermissionProfile external -> new SandboxRuntimePolicy(
                SandboxRuntimePolicyKind.EXTERNAL,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                networkMode(external.network()),
                false,
                true
            );
        };
    }

    private SandboxRuntimePolicy managedPolicy(
        Path workspace,
        ManagedPermissionProfile managed,
        AdditionalPermissionProfile additionalPermissions
    ) {
        FileSystemPermissionPolicy mergedFileSystemPolicy = mergedFileSystemPolicy(
            managed.fileSystem(),
            supportedAdditionalFileSystemPolicy(additionalPermissions)
        );
        Projection projection = projectFileSystemPolicy(workspace, mergedFileSystemPolicy);
        return new SandboxRuntimePolicy(
            SandboxRuntimePolicyKind.MANAGED,
            projection.allowRead(),
            List.of(),
            projection.allowWrite(),
            List.of(),
            mergedNetworkMode(managed.network(), additionalPermissions.network()),
            options.failIfUnavailable(),
            options.autoAllowBashIfSandboxed()
        );
    }

    private NetworkMode mergedNetworkMode(
        NetworkPermissionPolicy base,
        Optional<NetworkPermissionPolicy> additional
    ) {
        NetworkMode baseMode = networkMode(base);
        return additional
            .filter(policy -> policy.mode() == NetworkPolicyMode.ENABLED)
            .map(ignored -> NetworkMode.HOST)
            .orElse(baseMode);
    }

    private Optional<FileSystemPermissionPolicy> supportedAdditionalFileSystemPolicy(
        AdditionalPermissionProfile additionalPermissions
    ) {
        Optional<FileSystemPermissionPolicy> policy = additionalPermissions.fileSystem();
        policy.ifPresent(this::validateAdditionalFileSystemPolicy);
        return policy;
    }

    private FileSystemPermissionPolicy mergedFileSystemPolicy(
        FileSystemPermissionPolicy base,
        Optional<FileSystemPermissionPolicy> additional
    ) {
        if (additional.isEmpty()) {
            return base;
        }
        FileSystemPermissionPolicy extra = additional.orElseThrow();
        if (base.kind() != FileSystemPolicyKind.RESTRICTED) {
            return base;
        }
        List<FileSystemPermissionEntry> entries = new ArrayList<>(base.entries());
        entries.addAll(extra.entries());
        return FileSystemPermissionPolicy.restricted(entries);
    }

    private Projection projectFileSystemPolicy(Path workspace, FileSystemPermissionPolicy policy) {
        if (policy.kind() == FileSystemPolicyKind.UNRESTRICTED) {
            return new Projection(List.of(Path.of("/")), List.of(Path.of("/")));
        }
        if (policy.kind() != FileSystemPolicyKind.RESTRICTED) {
            throw new IllegalArgumentException("managed profile filesystem policy is unsupported: " + policy.kind());
        }
        LinkedHashSet<Path> allowRead = new LinkedHashSet<>();
        LinkedHashSet<Path> allowWrite = new LinkedHashSet<>();
        for (FileSystemPermissionEntry entry : policy.entries()) {
            List<Path> resolvedPaths = resolveEntryPaths(workspace, entry);
            if (entry.access() == FileSystemAccessMode.READ) {
                allowRead.addAll(resolvedPaths);
            } else if (entry.access() == FileSystemAccessMode.WRITE) {
                allowWrite.addAll(resolvedPaths);
            }
        }
        return new Projection(List.copyOf(allowRead), List.copyOf(allowWrite));
    }

    private List<Path> resolveEntryPaths(Path workspace, FileSystemPermissionEntry entry) {
        return switch (entry.path().kind()) {
            case SPECIAL -> resolveSpecialPath(workspace, FileSystemSpecialPath.fromJson(entry.path().value()));
            case EXACT_PATH -> List.of(resolveExactPath(workspace, entry.path().value()));
            case GLOB_PATTERN -> throw new IllegalArgumentException("sandbox projection does not support glob path: " + entry.path().value());
        };
    }

    private List<Path> resolveSpecialPath(Path workspace, FileSystemSpecialPath specialPath) {
        return switch (specialPath) {
            case ROOT -> List.of(Path.of("/"));
            case MINIMAL -> SandboxPlatformPaths.defaultReadOnlyPaths();
            case PROJECT_ROOTS -> List.of(workspace);
            case TMPDIR -> existingPath(Path.of(System.getProperty("java.io.tmpdir")), "tmpdir")
                .map(List::of)
                .orElseGet(List::of);
            case SLASH_TMP -> existingPath(Path.of("/tmp"), "slash_tmp")
                .map(List::of)
                .orElseGet(List::of);
        };
    }

    private Path resolveExactPath(Path workspace, String configuredPath) {
        Path path = Path.of(configuredPath);
        Path absolute = path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : workspace.resolve(path).normalize();
        if (!Files.exists(absolute)) {
            return absolute;
        }
        return realPath(absolute, "filesystem path");
    }

    private Optional<Path> existingPath(Path path, String label) {
        try {
            return Optional.of(path.toAbsolutePath().normalize().toRealPath());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private void validateAdditionalFileSystemPolicy(FileSystemPermissionPolicy policy) {
        if (policy.kind() != FileSystemPolicyKind.RESTRICTED) {
            throw new IllegalArgumentException("additional filesystem policy only supports RESTRICTED.");
        }
        for (FileSystemPermissionEntry entry : policy.entries()) {
            if (entry.path().kind() != FileSystemPath.Kind.EXACT_PATH) {
                throw new IllegalArgumentException("additional filesystem entries only support EXACT_PATH.");
            }
            if (entry.access() == FileSystemAccessMode.DENY) {
                throw new IllegalArgumentException("additional filesystem entries do not support DENY.");
            }
        }
    }

    private NetworkMode networkMode(NetworkPermissionPolicy policy) {
        if (policy == null || policy.mode() == NetworkPolicyMode.RESTRICTED) {
            return NetworkMode.DISABLED;
        }
        return NetworkMode.HOST;
    }

    private Path realPath(Path path, String label) {
        Objects.requireNonNull(path, label + " must not be null");
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " 不存在或不可访问: " + path, exception);
        }
    }

    private record Projection(List<Path> allowRead, List<Path> allowWrite) {
        private Projection {
            allowRead = List.copyOf(allowRead);
            allowWrite = List.copyOf(allowWrite);
        }
    }
}
