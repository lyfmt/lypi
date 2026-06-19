package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.NetworkPolicyMode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 生成 Bubblewrap 第一版白名单挂载策略。
 */
public final class DefaultSandboxPolicyResolver implements SandboxPolicyResolver {
    private final SandboxPolicyOptions options;

    public DefaultSandboxPolicyResolver(SandboxPolicyOptions options) {
        this.options = options == null ? SandboxPolicyOptions.defaults() : options;
    }

    @Override
    public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        Path realWorkspace = realPath(workspace, "workspace");
        realPath(cwd, "cwd");
        return new SandboxRuntimePolicy(
            SandboxPlatformPaths.defaultReadOnlyPaths(),
            List.of(),
            List.of(realWorkspace),
            List.of(),
            options.networkMode(),
            options.failIfUnavailable(),
            options.autoAllowBashIfSandboxed()
        );
    }

    @Override
    public SandboxRuntimePolicy resolve(Path workspace, Path cwd, AdditionalPermissionProfile additionalPermissions) {
        SandboxRuntimePolicy basePolicy = resolve(workspace, cwd);
        AdditionalPermissionProfile safeAdditionalPermissions = additionalPermissions == null
            ? AdditionalPermissionProfile.empty()
            : additionalPermissions;
        LinkedHashSet<Path> allowRead = new LinkedHashSet<>(basePolicy.allowRead());
        LinkedHashSet<Path> allowWrite = new LinkedHashSet<>(basePolicy.allowWrite());
        safeAdditionalPermissions.fileSystem().ifPresent(policy ->
            appendAdditionalFileSystemPermissions(policy, workspace, allowRead, allowWrite)
        );
        return new SandboxRuntimePolicy(
            basePolicy.kind(),
            List.copyOf(allowRead),
            basePolicy.denyRead(),
            List.copyOf(allowWrite),
            basePolicy.denyWrite(),
            safeAdditionalPermissions.network()
                .filter(policy -> policy.mode() == NetworkPolicyMode.ENABLED)
                .map(ignored -> cn.lypi.contracts.runtime.NetworkMode.HOST)
                .orElse(basePolicy.networkMode()),
            basePolicy.failIfUnavailable(),
            basePolicy.autoAllowBashIfSandboxed()
        );
    }

    private void appendAdditionalFileSystemPermissions(
        FileSystemPermissionPolicy policy,
        Path workspace,
        LinkedHashSet<Path> allowRead,
        LinkedHashSet<Path> allowWrite
    ) {
        validateAdditionalFileSystemPolicy(policy);
        Path realWorkspace = realPath(workspace, "workspace");
        for (FileSystemPermissionEntry entry : policy.entries()) {
            Path path = resolveExactPath(realWorkspace, entry.path().value());
            if (entry.access() == FileSystemAccessMode.READ) {
                allowRead.add(path);
            } else if (entry.access() == FileSystemAccessMode.WRITE) {
                allowWrite.add(path);
            }
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

    private Path resolveExactPath(Path workspace, String configuredPath) {
        Path path = Path.of(configuredPath);
        Path absolute = path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : workspace.resolve(path).normalize();
        try {
            return absolute.toRealPath();
        } catch (IOException exception) {
            return absolute;
        }
    }

    private Path realPath(Path path, String label) {
        Objects.requireNonNull(path, label + " must not be null");
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " 不存在或不可访问: " + path, exception);
        }
    }
}
