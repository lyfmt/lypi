package cn.lypi.security;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class PathSafetyChecker {
    private static final List<String> PATH_FIELDS = List.of(
        "path",
        "filePath",
        "targetPath",
        "sourcePath",
        "destinationPath"
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

    private Optional<PermissionDecision> checkPath(String fieldName, String rawPath, ToolUseContext context) {
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
        Optional<Path> realPath = realCwd.flatMap(path -> realPathForSafetyCheck(target, cwd));
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

    private Optional<Path> realPathForSafetyCheck(Path target, Path cwd) {
        Optional<Path> symlinkTarget = escapingSymlinkTarget(target, cwd);
        if (symlinkTarget.isPresent()) {
            return symlinkTarget;
        }
        Path probe = target;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            if (probe.equals(cwd)) {
                return Optional.empty();
            }
            probe = probe.getParent();
        }
        if (probe == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(probe.toRealPath());
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<Path> escapingSymlinkTarget(Path target, Path cwd) {
        Path relativeTarget = cwd.relativize(target);
        Path current = cwd;
        for (Path segment : relativeTarget) {
            current = current.resolve(segment).normalize();
            if (!Files.isSymbolicLink(current)) {
                continue;
            }
            try {
                Path linkTarget = Files.readSymbolicLink(current);
                Path resolved = linkTarget.isAbsolute()
                    ? linkTarget.normalize()
                    : current.getParent().resolve(linkTarget).normalize();
                return Optional.of(resolved);
            } catch (IOException exception) {
                return Optional.of(current);
            }
        }
        return Optional.empty();
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
