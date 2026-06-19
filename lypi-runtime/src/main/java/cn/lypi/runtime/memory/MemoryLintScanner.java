package cn.lypi.runtime.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 后台 memory 沉淀后的轻量结构诊断。
 */
public final class MemoryLintScanner {
    private final Path cwd;

    public MemoryLintScanner(Path cwd) {
        this.cwd = cwd == null ? Path.of(".").toAbsolutePath().normalize() : cwd.toAbsolutePath().normalize();
    }

    /**
     * 检查本次涉及的 memory 文件和对应索引。
     */
    public List<MemoryLintDiagnostic> scan(List<Path> changedPaths) {
        if (changedPaths == null || changedPaths.isEmpty()) {
            return List.of();
        }
        List<MemoryLintDiagnostic> diagnostics = new ArrayList<>();
        for (Path changedPath : changedPaths) {
            Path path = normalize(changedPath);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                String content = Files.readString(path);
                scanFile(path, content, diagnostics);
            } catch (IOException | RuntimeException exception) {
                diagnostics.add(new MemoryLintDiagnostic("scan-failed", path, exception.getMessage()));
            }
        }
        return diagnostics;
    }

    /**
     * 扫描当前项目已有 memory 结构，用于沉淀前提示。
     */
    public MemoryPreflightScan scanAll() throws IOException {
        List<Path> manifests = existingManifests();
        List<Path> memoryPaths = allMemoryPaths();
        return new MemoryPreflightScan(manifests, memoryPaths, scan(memoryPaths));
    }

    private void scanFile(Path path, String content, List<MemoryLintDiagnostic> diagnostics) throws IOException {
        if (isTopicMemory(path)) {
            scanTopicMemory(path, content, diagnostics);
        }
        if (isSkillMemory(path)) {
            scanSkillMemory(path, content, diagnostics);
        }
    }

    private void scanTopicMemory(Path path, String content, List<MemoryLintDiagnostic> diagnostics) throws IOException {
        if (!hasFrontmatter(content)) {
            diagnostics.add(new MemoryLintDiagnostic("missing-frontmatter", path, "topic memory must start with YAML frontmatter"));
        } else if (!frontmatter(content).toLowerCase(Locale.ROOT).contains("layer: l2")) {
            diagnostics.add(new MemoryLintDiagnostic("invalid-layer", path, "project topic memory must use layer: L2"));
        }
        scanDuplicateItems(path, content, diagnostics);
        if (hasAnyMemoryIndex() && !isListedInAnyIndex(path)) {
            diagnostics.add(new MemoryLintDiagnostic("missing-index-entry", path, "topic memory is not listed in .ly-pi/memory.md"));
        }
    }

    private void scanSkillMemory(Path path, String content, List<MemoryLintDiagnostic> diagnostics) {
        if (!hasFrontmatter(content)) {
            diagnostics.add(new MemoryLintDiagnostic("missing-frontmatter", path, "skill memory must start with YAML frontmatter"));
            return;
        }
        String metadata = frontmatter(content).toLowerCase(Locale.ROOT);
        if (metadata.contains("layer:") && !metadata.contains("layer: l3")) {
            diagnostics.add(new MemoryLintDiagnostic("invalid-layer", path, "skill memory must use layer: L3"));
        }
        String description = frontmatterValue(content, "description").toLowerCase(Locale.ROOT);
        String body = body(content).toLowerCase(Locale.ROOT);
        if (!description.isBlank() && hasEnoughComparableTokens(description, body) && !sharesMeaningfulToken(description, body)) {
            diagnostics.add(new MemoryLintDiagnostic(
                "skill-description-mismatch",
                path,
                "skill description does not appear to match body topic"
            ));
        }
    }

    private void scanDuplicateItems(Path path, String content, List<MemoryLintDiagnostic> diagnostics) {
        Set<String> seen = new HashSet<>();
        for (String line : body(content).split("\\R")) {
            String normalized = line.strip().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("- ")) {
                continue;
            }
            if (!seen.add(normalized)) {
                diagnostics.add(new MemoryLintDiagnostic("duplicate-entry", path, "duplicate memory item: " + line.strip()));
                return;
            }
        }
    }

    private boolean sharesMeaningfulToken(String description, String body) {
        Set<String> bodyTokens = tokens(body);
        for (String token : tokens(description)) {
            if (bodyTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEnoughComparableTokens(String description, String body) {
        return !tokens(description).isEmpty() && !tokens(body).isEmpty();
    }

    private Set<String> tokens(String value) {
        Set<String> tokens = new HashSet<>();
        for (String token : value.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 3 && !Set.of("when", "with", "using", "this", "that").contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String relativeForIndex(Path path) {
        String relative = cwd.resolve(".ly-pi").normalize().relativize(path).toString().replace('\\', '/');
        return relative.startsWith("memory/") ? relative : path.getFileName().toString();
    }

    private boolean hasAnyMemoryIndex() {
        return existingManifests().stream().anyMatch(Files::isRegularFile);
    }

    private boolean isListedInAnyIndex(Path path) throws IOException {
        String relativeFromLyPi = relativeForIndex(path);
        String relativeFromCwd = cwd.relativize(path).toString().replace('\\', '/');
        for (Path index : existingManifests()) {
            if (!Files.isRegularFile(index)) {
                continue;
            }
            String content = Files.readString(index);
            if (content.contains(relativeFromLyPi) || content.contains(relativeFromCwd) || content.contains(path.getFileName().toString())) {
                return true;
            }
        }
        return false;
    }

    private List<Path> existingManifests() {
        return List.of(cwd.resolve("MEMORY.md").normalize(), cwd.resolve(".ly-pi/memory.md").normalize())
            .stream()
            .filter(Files::isRegularFile)
            .toList();
    }

    private List<Path> allMemoryPaths() throws IOException {
        List<Path> roots = List.of(cwd.resolve(".ly-pi/memory").normalize(), cwd.resolve(".ly-pi/skills").normalize());
        List<Path> paths = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                paths.addAll(stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName() != null && path.getFileName().toString().endsWith(".md"))
                    .map(path -> path.toAbsolutePath().normalize())
                    .sorted(Comparator.comparing(Path::toString))
                    .toList());
            }
        }
        return paths;
    }

    private boolean isTopicMemory(Path path) {
        return path.startsWith(cwd.resolve(".ly-pi/memory").normalize()) && path.getFileName().toString().endsWith(".md");
    }

    private boolean isSkillMemory(Path path) {
        return path.startsWith(cwd.resolve(".ly-pi/skills").normalize()) && "SKILL.md".equals(path.getFileName().toString());
    }

    private boolean hasFrontmatter(String content) {
        return content != null && content.startsWith("---\n") && content.indexOf("\n---", 4) > 0;
    }

    private String frontmatter(String content) {
        if (!hasFrontmatter(content)) {
            return "";
        }
        int end = content.indexOf("\n---", 4);
        return content.substring(4, end);
    }

    private String body(String content) {
        if (!hasFrontmatter(content)) {
            return content == null ? "" : content;
        }
        int end = content.indexOf("\n---", 4);
        return content.substring(Math.min(content.length(), end + 4));
    }

    private String frontmatterValue(String content, String key) {
        String prefix = key.toLowerCase(Locale.ROOT) + ":";
        for (String line : frontmatter(content).split("\\R")) {
            String stripped = line.strip();
            if (stripped.toLowerCase(Locale.ROOT).startsWith(prefix)) {
                return stripped.substring(prefix.length()).strip();
            }
        }
        return "";
    }

    private Path normalize(Path path) {
        Path absolute = path.isAbsolute() ? path : cwd.resolve(path);
        return absolute.toAbsolutePath().normalize();
    }
}
