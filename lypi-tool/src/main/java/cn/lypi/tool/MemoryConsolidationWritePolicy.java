package cn.lypi.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 限制后台记忆沉淀可写入的路径。
 */
public final class MemoryConsolidationWritePolicy {
    private final Path cwd;
    private final Path userRoot;
    private final Path userMemoryIndex;
    private final Path userMemoryDirectory;
    private final Path projectMemoryFile;
    private final Path projectRootMemory;
    private final Path projectMemoryDirectory;
    private final Path projectSkillsDirectory;

    public MemoryConsolidationWritePolicy(Path cwd) {
        this(cwd, defaultUserRoot());
    }

    public MemoryConsolidationWritePolicy(Path cwd, Path userRoot) {
        this.cwd = normalize(Objects.requireNonNull(cwd, "cwd must not be null"));
        this.userRoot = normalize(Objects.requireNonNull(userRoot, "userRoot must not be null"));
        this.userMemoryIndex = this.userRoot.resolve("memory.md").normalize();
        this.userMemoryDirectory = this.userRoot.resolve("memory").normalize();
        this.projectMemoryFile = this.cwd.resolve("MEMORY.md").normalize();
        this.projectRootMemory = this.cwd.resolve(".ly-pi").resolve("memory.md").normalize();
        this.projectMemoryDirectory = this.cwd.resolve(".ly-pi").resolve("memory").normalize();
        this.projectSkillsDirectory = this.cwd.resolve(".ly-pi").resolve("skills").normalize();
    }

    /**
     * 返回目标路径是否允许被后台沉淀写入。
     */
    public boolean isAllowedWritePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        Path path = normalizeAgainstCwd(Path.of(rawPath));
        Path lexicalAllowedRoot = lexicalAllowedRoot(path);
        return lexicalAllowedRoot != null && isRealPathInsideAllowedRoot(path, lexicalAllowedRoot);
    }

    private boolean isProjectSkillFile(Path path) {
        return startsBelow(path, projectSkillsDirectory)
            && path.getFileName() != null
            && "SKILL.md".equals(path.getFileName().toString());
    }

    private Path lexicalAllowedRoot(Path path) {
        if (path.equals(userMemoryIndex)) {
            return userMemoryIndex.getParent();
        }
        if (startsBelow(path, userMemoryDirectory)) {
            return userMemoryDirectory;
        }
        if (path.equals(projectMemoryFile)) {
            return projectMemoryFile.getParent();
        }
        if (path.equals(projectRootMemory)) {
            return projectRootMemory.getParent();
        }
        if (startsBelow(path, projectMemoryDirectory)) {
            return projectMemoryDirectory;
        }
        if (isProjectSkillFile(path)) {
            return projectSkillsDirectory;
        }
        return null;
    }

    private boolean isRealPathInsideAllowedRoot(Path path, Path allowedRoot) {
        try {
            if (containsSymlinkInsideMemoryRoot(allowedRoot)) {
                return false;
            }
            Path existing = existingPath(path);
            Path realExisting = existing.toRealPath();
            Path realAllowedRoot = Files.exists(allowedRoot) ? allowedRoot.toRealPath() : existingPath(allowedRoot).toRealPath();
            if (Files.exists(path)) {
                return realExisting.startsWith(realAllowedRoot);
            }
            return realExisting.startsWith(realAllowedRoot)
                && path.toAbsolutePath().normalize().startsWith(existing.toAbsolutePath().normalize());
        } catch (IOException | RuntimeException exception) {
            return false;
        }
    }

    private boolean containsSymlinkInsideMemoryRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        Path current = symlinkCheckBase(normalized);
        if (current == null) {
            return false;
        }
        Path relative = current.relativize(normalized);
        for (Path segment : relative) {
            current = current == null ? segment : current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    private Path symlinkCheckBase(Path path) {
        if (path.startsWith(cwd)) {
            return cwd;
        }
        if (path.startsWith(userRoot)) {
            return userRoot;
        }
        return path.getParent();
    }

    private Path existingPath(Path path) {
        Path current = path;
        while (current != null && !Files.exists(current)) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalArgumentException("no existing parent for path " + path);
        }
        return current;
    }

    private Path normalizeAgainstCwd(Path path) {
        Path absolute = path.isAbsolute() ? path : cwd.resolve(path);
        return normalize(absolute);
    }

    private static boolean startsBelow(Path path, Path directory) {
        return path.startsWith(directory) && !path.equals(directory);
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static Path defaultUserRoot() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            return Path.of(".ly-pi");
        }
        return Path.of(home).resolve(".ly-pi");
    }
}
