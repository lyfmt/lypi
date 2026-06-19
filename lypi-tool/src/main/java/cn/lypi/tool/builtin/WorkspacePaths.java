package cn.lypi.tool.builtin;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkspacePaths {
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_APPROVED_ADDITIONAL_PERMISSIONS = "approvedAdditionalPermissions";

    private WorkspacePaths() {
    }

    static Path resolvePath(Map<String, Object> input, ToolUseContext context, String fieldName) {
        return resolvePath(input, context, fieldName, null);
    }

    static Path resolvePath(
        Map<String, Object> input,
        ToolUseContext context,
        String fieldName,
        FileSystemAccessMode accessMode
    ) {
        Object raw = input.get(fieldName);
        String value = raw == null ? "." : raw.toString();
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path resolved = cwd.resolve(value).normalize();
        if (resolved.startsWith(cwd) || additionalFileSystemAllows(context, accessMode, resolved)) {
            return resolved;
        }
        throw new IllegalArgumentException("路径越过当前工作目录: " + value);
    }

    static String relativePath(Path path, ToolUseContext context) {
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(cwd)) {
            return normalized.toString();
        }
        String relative = cwd.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    static Path requireRealPathInsideWorkspace(Path path, ToolUseContext context) throws IOException {
        return requireRealPathInsideWorkspace(path, context, null);
    }

    static Path requireRealPathInsideWorkspace(
        Path path,
        ToolUseContext context,
        FileSystemAccessMode accessMode
    ) throws IOException {
        Path realCwd = context.cwd().toRealPath();
        Path realPath = path.toRealPath();
        if (realPath.startsWith(realCwd) || additionalFileSystemAllows(context, accessMode, path.toAbsolutePath().normalize())) {
            return realPath;
        }
        throw new IllegalArgumentException("路径经符号链接越过当前工作目录: " + relativePath(path, context));
    }

    static boolean realPathInsideWorkspace(Path path, ToolUseContext context) {
        return realPathInsideWorkspace(path, context, null);
    }

    static boolean realPathInsideWorkspace(Path path, ToolUseContext context, FileSystemAccessMode accessMode) {
        try {
            Path realCwd = context.cwd().toRealPath();
            Path realPath = path.toRealPath();
            return realPath.startsWith(realCwd)
                || additionalFileSystemAllows(context, accessMode, path.toAbsolutePath().normalize());
        } catch (IOException exception) {
            return false;
        }
    }

    static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Path temp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
    }

    private static boolean additionalFileSystemAllows(
        ToolUseContext context,
        FileSystemAccessMode accessMode,
        Path rawPath
    ) {
        if (accessMode == null) {
            return false;
        }
        Optional<FileSystemPermissionPolicy> policy = additionalFileSystemPolicy(context);
        return policy.filter(fileSystemPermissionPolicy ->
            fileSystemAllows(fileSystemPermissionPolicy, accessMode, rawPath, context)
        ).isPresent();
    }

    private static Optional<FileSystemPermissionPolicy> additionalFileSystemPolicy(ToolUseContext context) {
        // NOTE: approvedAdditionalPermissions 只能由 DefaultToolRuntime 在 request_permissions 批准后写入。
        if (!approvedAdditionalPermissions(context)) {
            return Optional.empty();
        }
        Object value = context.metadata().get(METADATA_ADDITIONAL_PERMISSIONS);
        if (value instanceof AdditionalPermissionProfile additionalPermissions) {
            return additionalPermissions.fileSystem().filter(WorkspacePaths::supportedAdditionalFileSystemPolicy);
        }
        return Optional.empty();
    }

    private static boolean supportedAdditionalFileSystemPolicy(FileSystemPermissionPolicy policy) {
        return policy.kind() == FileSystemPolicyKind.RESTRICTED
            && policy.entries().stream().allMatch(entry ->
                entry.path().kind() == FileSystemPath.Kind.EXACT_PATH
                    && entry.access() != FileSystemAccessMode.DENY
            );
    }

    private static boolean approvedAdditionalPermissions(ToolUseContext context) {
        Object value = context.metadata().get(METADATA_APPROVED_ADDITIONAL_PERMISSIONS);
        if (value instanceof Boolean approved) {
            return approved;
        }
        return value instanceof String approved && Boolean.parseBoolean(approved);
    }

    private static boolean fileSystemAllows(
        FileSystemPermissionPolicy policy,
        FileSystemAccessMode accessMode,
        Path rawPath,
        ToolUseContext context
    ) {
        Path normalizedPath = resolveAgainstCwd(rawPath, context.cwd());
        List<Path> candidates = candidatePaths(normalizedPath);
        if (policy.kind() == FileSystemPolicyKind.UNRESTRICTED) {
            return true;
        }
        if (policy.kind() == FileSystemPolicyKind.EXTERNAL_SANDBOX) {
            return false;
        }
        if (matches(policy.entries(), candidates, FileSystemAccessMode.DENY, context)) {
            return false;
        }
        if (allCandidatesMatch(policy.entries(), candidates, accessMode, context)) {
            return true;
        }
        return accessMode == FileSystemAccessMode.READ
            && allCandidatesMatch(policy.entries(), candidates, FileSystemAccessMode.WRITE, context);
    }

    private static boolean allCandidatesMatch(
        List<FileSystemPermissionEntry> entries,
        List<Path> candidates,
        FileSystemAccessMode access,
        ToolUseContext context
    ) {
        return candidates.stream().allMatch(candidate -> matches(entries, List.of(candidate), access, context));
    }

    private static boolean matches(
        List<FileSystemPermissionEntry> entries,
        List<Path> candidates,
        FileSystemAccessMode access,
        ToolUseContext context
    ) {
        return entries.stream()
            .filter(entry -> entry.access() == access)
            .anyMatch(entry -> matchesAnyCandidate(entry.path(), candidates, context));
    }

    private static boolean matchesAnyCandidate(
        FileSystemPath path,
        List<Path> candidates,
        ToolUseContext context
    ) {
        return candidates.stream().anyMatch(candidate -> matchesPath(path, candidate, context));
    }

    private static boolean matchesPath(FileSystemPath path, Path candidate, ToolUseContext context) {
        return switch (path.kind()) {
            case SPECIAL -> matchesSpecialPath(path, candidate, context);
            case EXACT_PATH -> matchesExactPath(path.value(), candidate, context.cwd());
            case GLOB_PATTERN -> matchesGlob(path.value(), candidate, context.cwd());
        };
    }

    private static boolean matchesSpecialPath(FileSystemPath path, Path candidate, ToolUseContext context) {
        FileSystemSpecialPath specialPath = FileSystemSpecialPath.fromJson(path.value());
        return switch (specialPath) {
            case ROOT -> true;
            case PROJECT_ROOTS -> isSameOrDescendant(candidate, context.cwd().toAbsolutePath().normalize());
            case TMPDIR -> isSameOrDescendant(candidate, Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
            case SLASH_TMP -> isSameOrDescendant(candidate, Path.of("/tmp").toAbsolutePath().normalize());
            case MINIMAL -> false;
        };
    }

    private static boolean matchesExactPath(String configuredPath, Path candidate, Path cwd) {
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

    private static boolean matchesGlob(String pattern, Path candidate, Path cwd) {
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

    private static Path resolveAgainstCwd(Path path, Path cwd) {
        Path base = cwd.toAbsolutePath().normalize();
        return path.isAbsolute()
            ? path.toAbsolutePath().normalize()
            : base.resolve(path).normalize();
    }

    private static List<Path> candidatePaths(Path normalizedPath) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(normalizedPath);
        Path resolvedPath = resolveSymlinkCandidates(normalizedPath);
        if (!resolvedPath.equals(normalizedPath)) {
            candidates.add(resolvedPath);
        }
        return List.copyOf(candidates);
    }

    private static Path resolveSymlinkCandidates(Path normalizedPath) {
        List<Path> segments = new ArrayList<>();
        for (Path segment : normalizedPath) {
            segments.add(segment);
        }
        Path root = normalizedPath.getRoot();
        Path start = root == null ? Path.of("") : root;
        return resolveSegments(start, segments, 0, 0).toAbsolutePath().normalize();
    }

    private static Path resolveSegments(Path current, List<Path> segments, int index, int symlinkDepth) {
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

    private static Path resolveCurrentSymlink(Path current, int symlinkDepth) {
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

    private static boolean isSameOrDescendant(Path candidate, Path allowedRoot) {
        return candidate.equals(allowedRoot) || candidate.startsWith(allowedRoot);
    }
}
