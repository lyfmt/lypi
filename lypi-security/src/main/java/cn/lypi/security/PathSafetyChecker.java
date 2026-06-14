package cn.lypi.security;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 检查工具输入中的路径硬安全线。
 *
 * NOTE: 该检查覆盖词法路径、符号链接链和受保护路径，不能被 BYPASS 越过。
 */
final class PathSafetyChecker {
    private static final List<String> PATH_FIELDS = List.of(
        "path",
        "filePath",
        "targetPath",
        "sourcePath",
        "destinationPath",
        "cwd"
    );
    private static final List<String> PROTECTED_PATH_PREFIXES = List.of(
        ".git",
        ".git/",
        ".gitconfig",
        ".gitmodules",
        ".mcp.json",
        ".claude.json",
        ".bashrc",
        ".zshrc",
        ".profile"
    );

    /**
     * 检查工具请求中的常见路径字段。
     */
    Optional<PermissionDecision> check(ToolUseRequest request, ToolUseContext context) {
        for (String fieldName : PATH_FIELDS) {
            Object rawPath = request.input().get(fieldName);
            if (rawPath == null) {
                continue;
            }
            Optional<PermissionDecision> decision = checkPath(fieldName, rawPath.toString(), context);
            if (decision.isPresent()) {
                return decision;
            }
        }
        return Optional.empty();
    }

    Optional<PermissionDecision> checkPath(String fieldName, String rawPath, ToolUseContext context) {
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path target = cwd.resolve(rawPath).normalize();
        if (!target.startsWith(cwd)) {
            return Optional.of(decision(
                "工具路径越过当前工作目录: " + rawPath,
                fieldName,
                rawPath,
                target
            ));
        }
        Optional<Path> realCwd = realPathForCwd(cwd);
        Optional<Path> realPath = realCwd.map(path -> realPathForSafetyCheck(rawPath, path));
        if (realPath.isPresent() && !realPath.get().startsWith(realCwd.get())) {
            return Optional.of(decision(
                "工具路径经符号链接越过当前工作目录: " + rawPath,
                fieldName,
                rawPath,
                realPath.get()
            ));
        }
        if (realPath.isPresent() && realPath.get().startsWith(realCwd.get())) {
            String realRelativePath = realCwd.get().relativize(realPath.get()).toString().replace('\\', '/');
            if (isProtectedPath(realRelativePath)) {
                return Optional.of(decision(
                    "工具路径经符号链接命中受保护路径: " + rawPath,
                    fieldName,
                    rawPath,
                    realPath.get()
                ));
            }
        }
        String relativePath = cwd.relativize(target).toString().replace('\\', '/');
        if (isProtectedPath(relativePath)) {
            return Optional.of(decision(
                "工具路径命中受保护路径: " + rawPath,
                fieldName,
                rawPath,
                target
            ));
        }
        return Optional.empty();
    }

    Optional<PermissionDecision> checkPathInsideWorkspace(
        String fieldName,
        String rawPath,
        ToolUseContext context,
        Path baseCwd
    ) {
        Path workspace = context.cwd().toAbsolutePath().normalize();
        Path base = baseCwd.toAbsolutePath().normalize();
        Path target = base.resolve(rawPath).normalize();
        if (!target.startsWith(workspace)) {
            return Optional.of(decision(
                "工具路径越过当前工作目录: " + rawPath,
                fieldName,
                rawPath,
                target
            ));
        }
        String relativePath = workspace.relativize(target).toString().replace('\\', '/');
        if (isProtectedPath(relativePath)) {
            return Optional.of(decision(
                "工具路径命中受保护路径: " + rawPath,
                fieldName,
                rawPath,
                target
            ));
        }
        return Optional.empty();
    }

    private Path realPathForSafetyCheck(String rawPath, Path realCwd) {
        Path raw = Path.of(rawPath);
        List<Path> segments = new ArrayList<>();
        for (Path segment : raw) {
            segments.add(segment);
        }
        Path current = raw.isAbsolute() ? raw.getRoot() : realCwd;
        return resolveSegments(current, segments, 0, 0).normalize();
    }

    private Path resolveSegments(Path current, List<Path> segments, int index, int symlinkDepth) {
        if (symlinkDepth > 40) {
            return Path.of("/");
        }
        Path resolvedCurrent = resolveCurrentSymlink(current, symlinkDepth);
        if (index >= segments.size()) {
            return resolvedCurrent;
        }
        String segment = segments.get(index).toString();
        if (".".equals(segment) || segment.isBlank()) {
            return resolveSegments(resolvedCurrent, segments, index + 1, symlinkDepth);
        }
        if ("..".equals(segment)) {
            Path parent = resolvedCurrent.getParent();
            return resolveSegments(parent == null ? resolvedCurrent : parent, segments, index + 1, symlinkDepth);
        }
        Path next = resolvedCurrent.resolve(segment).normalize();
        return resolveSymlink(next, segments, index + 1, symlinkDepth);
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
        if (depth > 40) {
            return Path.of("/");
        }
        return resolved;
    }

    private Path resolveSymlink(Path current, List<Path> remainingSegments, int nextIndex, int symlinkDepth) {
        if (!Files.isSymbolicLink(current)) {
            return resolveSegments(current, remainingSegments, nextIndex, symlinkDepth);
        }
        try {
            Path linkTarget = Files.readSymbolicLink(current);
            Path parent = current.getParent() == null ? current : current.getParent();
            Path resolved = linkTarget.isAbsolute()
                ? linkTarget.normalize()
                : parent.resolve(linkTarget).normalize();
            return resolveSegments(resolved, remainingSegments, nextIndex, symlinkDepth + 1);
        } catch (IOException exception) {
            return current;
        }
    }

    private Optional<Path> realPathForCwd(Path cwd) {
        try {
            return Optional.of(cwd.toRealPath());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private boolean isProtectedPath(String relativePath) {
        for (String prefix : PROTECTED_PATH_PREFIXES) {
            if (relativePath.equals(prefix) || relativePath.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private PermissionDecision decision(String message, String fieldName, String rawPath, Path normalizedPath) {
        return new PermissionDecision(
            PermissionBehavior.DENY,
            PermissionDecisionReason.PATH_SAFETY,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.of(
                "pathField", fieldName,
                "path", rawPath,
                "normalizedPath", normalizedPath.toString()
            )
        );
    }
}
