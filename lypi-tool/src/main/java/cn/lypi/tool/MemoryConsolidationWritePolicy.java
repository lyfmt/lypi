package cn.lypi.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 限制后台记忆沉淀可写入的路径。
 */
public final class MemoryConsolidationWritePolicy {
    private final Path cwd;
    private final Path userRoot;
    private final Path userMemoryIndex;
    private final Path userMemories;
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
        this.userMemories = this.userRoot.resolve("memories").normalize();
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
        return path.equals(userMemoryIndex)
            || startsBelow(path, userMemories)
            || path.equals(projectMemoryFile)
            || path.equals(projectRootMemory)
            || startsBelow(path, projectMemoryDirectory)
            || isProjectSkillFile(path);
    }

    private boolean isProjectSkillFile(Path path) {
        return startsBelow(path, projectSkillsDirectory)
            && path.getFileName() != null
            && "SKILL.md".equals(path.getFileName().toString());
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
