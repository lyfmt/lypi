package cn.lypi.tool.builtin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

final class RipgrepBinaryResolver {
    static final String MODE_KEY = "lypi.tool.grep.ripgrep.mode";

    private final RipgrepPlatform platform;
    private final Path resourceRoot;
    private final Path cacheRoot;
    private final ClassLoader classLoader;
    private final List<Path> systemSearchPath;

    private RipgrepBinaryResolver(
        RipgrepPlatform platform,
        Path resourceRoot,
        Path cacheRoot,
        ClassLoader classLoader,
        List<Path> systemSearchPath
    ) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.resourceRoot = absolutePath(resourceRoot, "resourceRoot");
        this.cacheRoot = absolutePath(cacheRoot, "cacheRoot");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
        this.systemSearchPath = normalizeSearchPath(systemSearchPath);
    }

    static RipgrepBinaryResolver defaults() {
        return new RipgrepBinaryResolver(
            RipgrepPlatform.current(),
            Path.of("lypi-tool", "src", "main", "resources"),
            Path.of(".lypi", "cache", "ripgrep"),
            RipgrepBinaryResolver.class.getClassLoader(),
            defaultSystemSearchPath()
        );
    }

    static RipgrepBinaryResolver forTesting(RipgrepPlatform platform, Path resourceRoot) {
        return new RipgrepBinaryResolver(
            platform,
            resourceRoot,
            resourceRoot.resolve(".lypi-cache"),
            RipgrepBinaryResolver.class.getClassLoader(),
            List.of()
        );
    }

    static RipgrepBinaryResolver forTesting(
        RipgrepPlatform platform,
        Path resourceRoot,
        Path cacheRoot,
        ClassLoader classLoader
    ) {
        return new RipgrepBinaryResolver(platform, resourceRoot, cacheRoot, classLoader, List.of());
    }

    static RipgrepBinaryResolver forTesting(
        RipgrepPlatform platform,
        Path resourceRoot,
        Path cacheRoot,
        ClassLoader classLoader,
        List<Path> systemSearchPath
    ) {
        return new RipgrepBinaryResolver(platform, resourceRoot, cacheRoot, classLoader, systemSearchPath);
    }

    RipgrepBinary resolve(Map<String, ?> options) {
        String mode = mode(options);
        if ("system".equals(mode)) {
            return systemBinary();
        }
        String resourcePath = platform.resourcePath();
        Path binary = resourceRoot.resolve(resourcePath).normalize();
        if (Files.isRegularFile(binary) && Files.isExecutable(binary)) {
            return new RipgrepBinary(requireExecutableFile(binary, "随包").toString(), "vendor");
        }
        URL resource = classLoader.getResource(resourcePath);
        if (resource != null) {
            Path executable = requireExecutableFile(executableResource(resourcePath, resource), "随包");
            return new RipgrepBinary(executable.toString(), "vendor");
        }
        throw new IllegalStateException("未找到随包 ripgrep: " + platform.platformId()
            + "，可临时设置 " + MODE_KEY + "=system 使用系统 rg。");
    }

    private Path executableResource(String resourcePath, URL resource) {
        Path direct = directPath(resource);
        if (direct != null && Files.isRegularFile(direct)) {
            makeExecutable(direct);
            return requireExecutableFile(direct, "随包");
        }
        Path cached = cacheRoot.resolve("current").resolve(platform.platformId()).resolve(platform.executableName());
        if (Files.isRegularFile(cached) && Files.isExecutable(cached)) {
            return requireExecutableFile(cached, "缓存");
        }
        try {
            Files.createDirectories(cached.getParent());
            try (InputStream input = resource.openStream()) {
                Files.copy(input, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            makeExecutable(cached);
            return requireExecutableFile(cached, "缓存");
        } catch (IOException exception) {
            throw new IllegalStateException("无法缓存随包 ripgrep: " + resourcePath + " -> " + cached, exception);
        }
    }

    private RipgrepBinary systemBinary() {
        for (Path directory : systemSearchPath) {
            Path candidate = directory.resolve(platform.executableName()).normalize();
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return new RipgrepBinary(requireExecutableFile(candidate, "系统").toString(), "system");
            }
        }
        throw new IllegalStateException("未找到可执行的系统 ripgrep: " + platform.platformId());
    }

    private Path directPath(URL resource) {
        if (!"file".equals(resource.getProtocol())) {
            return null;
        }
        try {
            return Path.of(resource.toURI());
        } catch (IllegalArgumentException | FileSystemNotFoundException | URISyntaxException exception) {
            return null;
        }
    }

    private void makeExecutable(Path binary) {
        if (!binary.toFile().setExecutable(true, true) && !Files.isExecutable(binary)) {
            throw new IllegalStateException("无法设置 ripgrep 可执行权限: " + binary);
        }
    }

    private Path requireExecutableFile(Path candidate, String source) {
        Path absolute = candidate.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute) || !Files.isExecutable(absolute)) {
            throw new IllegalStateException(source + " ripgrep 不是可执行普通文件: " + absolute);
        }
        try {
            return absolute.toRealPath();
        } catch (IOException exception) {
            throw new IllegalStateException("无法解析" + source + " ripgrep: " + absolute, exception);
        }
    }

    private static Path absolutePath(Path path, String name) {
        return Objects.requireNonNull(path, name + " must not be null").toAbsolutePath().normalize();
    }

    private static List<Path> normalizeSearchPath(List<Path> searchPath) {
        if (searchPath == null || searchPath.isEmpty()) {
            return List.of();
        }
        return searchPath.stream()
            .filter(Objects::nonNull)
            .map(path -> path.toAbsolutePath().normalize())
            .distinct()
            .toList();
    }

    private static List<Path> defaultSystemSearchPath() {
        String value = System.getenv("PATH");
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (String entry : value.split(Pattern.quote(File.pathSeparator))) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                paths.add(Path.of(entry));
            } catch (InvalidPathException ignored) {
                // Ignore malformed PATH entries and continue searching valid directories.
            }
        }
        return List.copyOf(paths);
    }

    private String mode(Map<String, ?> options) {
        Object option = options == null ? null : options.get(MODE_KEY);
        String value = option == null ? System.getProperty(MODE_KEY) : option.toString();
        if (value == null || value.isBlank()) {
            value = System.getenv("LYPI_TOOL_GREP_RIPGREP_MODE");
        }
        return value == null || value.isBlank() ? "vendor" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
