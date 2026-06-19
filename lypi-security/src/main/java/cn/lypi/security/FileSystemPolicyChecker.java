package cn.lypi.security;

import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionProfile;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 检查 permission profile 的文件系统边界。
 */
public final class FileSystemPolicyChecker {
    /**
     * 根据 profile 文件系统策略判定路径访问。
     *
     * NOTE: 调用方仍需先执行 PathSafetyChecker，hard safety 不在这里放宽。
     */
    public PermissionDecision decide(
        PermissionProfile profile,
        FileSystemAccessMode requestedAccess,
        Path rawPath,
        ToolUseContext context
    ) {
        Objects.requireNonNull(profile, "profile");
        return decide(profile.fileSystem(), requestedAccess, rawPath, context);
    }

    /**
     * 根据文件系统策略判定路径访问。
     */
    public PermissionDecision decide(
        FileSystemPermissionPolicy policy,
        FileSystemAccessMode requestedAccess,
        Path rawPath,
        ToolUseContext context
    ) {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(requestedAccess, "requestedAccess");
        Objects.requireNonNull(rawPath, "rawPath");
        Objects.requireNonNull(context, "context");
        Path normalizedPath = resolveAgainstCwd(rawPath, context.cwd());
        List<Path> candidates = candidatePaths(normalizedPath);
        return switch (policy.kind()) {
            case UNRESTRICTED -> allow("文件系统 profile 允许访问。", normalizedPath);
            case EXTERNAL_SANDBOX -> deny("external filesystem profile 必须由外部 sandbox 判定。", normalizedPath);
            case RESTRICTED -> restrictedDecision(policy, requestedAccess, normalizedPath, candidates, context);
        };
    }

    private PermissionDecision restrictedDecision(
        FileSystemPermissionPolicy policy,
        FileSystemAccessMode requestedAccess,
        Path normalizedPath,
        List<Path> candidates,
        ToolUseContext context
    ) {
        if (matches(policy.entries(), candidates, FileSystemAccessMode.DENY, context)) {
            return deny("文件系统 profile 拒绝访问路径。", normalizedPath);
        }
        if (allCandidatesMatch(policy.entries(), candidates, requestedAccess, context)) {
            return allow("文件系统 profile 允许访问路径。", normalizedPath);
        }
        if (requestedAccess == FileSystemAccessMode.READ
            && allCandidatesMatch(policy.entries(), candidates, FileSystemAccessMode.WRITE, context)) {
            return allow("文件系统 profile 写权限包含读访问。", normalizedPath);
        }
        return deny("文件系统 profile 未允许访问路径。", normalizedPath);
    }

    private boolean allCandidatesMatch(
        List<FileSystemPermissionEntry> entries,
        List<Path> candidates,
        FileSystemAccessMode access,
        ToolUseContext context
    ) {
        return candidates.stream().allMatch(candidate -> matches(entries, List.of(candidate), access, context));
    }

    private boolean matches(
        List<FileSystemPermissionEntry> entries,
        List<Path> candidates,
        FileSystemAccessMode access,
        ToolUseContext context
    ) {
        return entries.stream()
            .filter(entry -> entry.access() == access)
            .anyMatch(entry -> matchesAnyCandidate(entry.path(), candidates, context));
    }

    private boolean matchesAnyCandidate(
        FileSystemPath path,
        List<Path> candidates,
        ToolUseContext context
    ) {
        return candidates.stream().anyMatch(candidate -> matchesPath(path, candidate, context));
    }

    private boolean matchesPath(FileSystemPath path, Path candidate, ToolUseContext context) {
        return switch (path.kind()) {
            case SPECIAL -> matchesSpecialPath(path, candidate, context);
            case EXACT_PATH -> matchesExactPath(path.value(), candidate, context.cwd());
            case GLOB_PATTERN -> matchesGlob(path.value(), candidate, context.cwd());
        };
    }

    private boolean matchesSpecialPath(FileSystemPath path, Path candidate, ToolUseContext context) {
        FileSystemSpecialPath specialPath = FileSystemSpecialPath.fromJson(path.value());
        return switch (specialPath) {
            case ROOT -> true;
            case PROJECT_ROOTS -> matchesWorkspaceRoot(candidate, context.cwd());
            case TMPDIR -> isSameOrDescendant(candidate, Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
            case SLASH_TMP -> isSameOrDescendant(candidate, Path.of("/tmp").toAbsolutePath().normalize());
            case MINIMAL -> false;
        };
    }

    private boolean matchesWorkspaceRoot(Path candidate, Path cwd) {
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        if (isSameOrDescendant(candidate, normalizedCwd)) {
            return true;
        }
        try {
            return isSameOrDescendant(candidate, normalizedCwd.toRealPath());
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean matchesExactPath(String configuredPath, Path candidate, Path cwd) {
        Path exactPath = resolveAgainstCwd(Path.of(configuredPath), cwd);
        if (isSameOrDescendant(candidate, exactPath)) {
            return true;
        }
        try {
            Path realCwd = cwd.toAbsolutePath().normalize().toRealPath();
            Path realExactPath = Path.of(configuredPath).isAbsolute()
                ? Path.of(configuredPath).toAbsolutePath().normalize()
                : realCwd.resolve(configuredPath).normalize();
            return isSameOrDescendant(candidate, realExactPath);
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean matchesGlob(String pattern, Path candidate, Path cwd) {
        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
        Path normalizedCwd = cwd.toAbsolutePath().normalize();
        if (candidate.startsWith(normalizedCwd) && matcher.matches(normalizedCwd.relativize(candidate))) {
            return true;
        }
        try {
            Path realCwd = normalizedCwd.toRealPath();
            return candidate.startsWith(realCwd) && matcher.matches(realCwd.relativize(candidate));
        } catch (IOException exception) {
            return false;
        }
    }

    private Path resolveAgainstCwd(Path path, Path cwd) {
        Path base = cwd.toAbsolutePath().normalize();
        return path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : base.resolve(path).normalize();
    }

    private List<Path> candidatePaths(Path normalizedPath) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(normalizedPath);
        Path resolvedPath = resolveSymlinkCandidates(normalizedPath);
        if (!resolvedPath.equals(normalizedPath)) {
            candidates.add(resolvedPath);
        }
        return List.copyOf(candidates);
    }

    private Path resolveSymlinkCandidates(Path normalizedPath) {
        List<Path> segments = new ArrayList<>();
        for (Path segment : normalizedPath) {
            segments.add(segment);
        }
        Path root = normalizedPath.getRoot();
        Path start = root == null ? Path.of("") : root;
        return resolveSegments(start, segments, 0, 0).toAbsolutePath().normalize();
    }

    private Path resolveSegments(Path current, List<Path> segments, int index, int symlinkDepth) {
        if (symlinkDepth > 40 || index >= segments.size()) {
            return current;
        }
        Path resolvedCurrent = resolveCurrentSymlink(current, symlinkDepth);
        String segment = segments.get(index).toString();
        if (".".equals(segment) || segment.isBlank()) {
            return resolveSegments(resolvedCurrent, segments, index + 1, symlinkDepth);
        }
        if ("..".equals(segment)) {
            Path parent = resolvedCurrent.getParent();
            return resolveSegments(parent == null ? resolvedCurrent : parent, segments, index + 1, symlinkDepth);
        }
        Path next = resolvedCurrent.resolve(segment).normalize();
        if (!Files.isSymbolicLink(next)) {
            return resolveSegments(next, segments, index + 1, symlinkDepth);
        }
        try {
            Path linkTarget = Files.readSymbolicLink(next);
            Path parent = next.getParent() == null ? next : next.getParent();
            Path resolved = linkTarget.isAbsolute()
                ? linkTarget.normalize()
                : parent.resolve(linkTarget).normalize();
            return resolveSegments(resolved, segments, index + 1, symlinkDepth + 1);
        } catch (IOException exception) {
            return next;
        }
    }

    private Path resolveCurrentSymlink(Path current, int symlinkDepth) {
        Path resolved = current;
        int depth = symlinkDepth;
        while (Files.isSymbolicLink(resolved) && depth <= 40) {
            try {
                Path linkTarget = Files.readSymbolicLink(resolved);
                Path parent = resolved.getParent() == null ? resolved : resolved.getParent();
                resolved = linkTarget.isAbsolute()
                    ? linkTarget.normalize()
                    : parent.resolve(linkTarget).normalize();
                depth++;
            } catch (IOException exception) {
                return resolved;
            }
        }
        return resolved;
    }

    private boolean isSameOrDescendant(Path candidate, Path allowedRoot) {
        return candidate.equals(allowedRoot) || candidate.startsWith(allowedRoot);
    }

    private PermissionDecision allow(String message, Path normalizedPath) {
        return decision(PermissionBehavior.ALLOW, message, normalizedPath);
    }

    private PermissionDecision deny(String message, Path normalizedPath) {
        return decision(PermissionBehavior.DENY, message, normalizedPath);
    }

    private PermissionDecision decision(PermissionBehavior behavior, String message, Path normalizedPath) {
        return new PermissionDecision(
            behavior,
            PermissionDecisionReason.SANDBOX_POLICY,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of("normalizedPath", normalizedPath.toString())
        );
    }
}
