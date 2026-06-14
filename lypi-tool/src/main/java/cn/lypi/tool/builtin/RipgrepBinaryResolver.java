package cn.lypi.tool.builtin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Objects;

final class RipgrepBinaryResolver {
    static final String MODE_KEY = "lypi.tool.grep.ripgrep.mode";

    private final RipgrepPlatform platform;
    private final Path resourceRoot;
    private final Path cacheRoot;
    private final ClassLoader classLoader;

    private RipgrepBinaryResolver(
        RipgrepPlatform platform,
        Path resourceRoot,
        Path cacheRoot,
        ClassLoader classLoader
    ) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.resourceRoot = Objects.requireNonNull(resourceRoot, "resourceRoot must not be null");
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot must not be null");
        this.classLoader = Objects.requireNonNull(classLoader, "classLoader must not be null");
    }

    static RipgrepBinaryResolver defaults() {
        return new RipgrepBinaryResolver(
            RipgrepPlatform.current(),
            Path.of("lypi-tool", "src", "main", "resources"),
            Path.of(".lypi", "cache", "ripgrep"),
            RipgrepBinaryResolver.class.getClassLoader()
        );
    }

    static RipgrepBinaryResolver forTesting(RipgrepPlatform platform, Path resourceRoot) {
        return new RipgrepBinaryResolver(
            platform,
            resourceRoot,
            resourceRoot.resolve(".lypi-cache"),
            RipgrepBinaryResolver.class.getClassLoader()
        );
    }

    static RipgrepBinaryResolver forTesting(
        RipgrepPlatform platform,
        Path resourceRoot,
        Path cacheRoot,
        ClassLoader classLoader
    ) {
        return new RipgrepBinaryResolver(platform, resourceRoot, cacheRoot, classLoader);
    }

    RipgrepBinary resolve(Map<String, ?> options) {
        String mode = mode(options);
        if ("system".equals(mode)) {
            return new RipgrepBinary("rg", "system");
        }
        String resourcePath = platform.resourcePath();
        Path binary = resourceRoot.resolve(resourcePath).normalize();
        if (Files.isRegularFile(binary) && Files.isExecutable(binary)) {
            return new RipgrepBinary(binary.toString(), "vendor");
        }
        URL resource = classLoader.getResource(resourcePath);
        if (resource != null) {
            return new RipgrepBinary(executableResource(resourcePath, resource).toString(), "vendor");
        }
        throw new IllegalStateException("未找到随包 ripgrep: " + platform.platformId()
            + "，可临时设置 " + MODE_KEY + "=system 使用系统 rg。");
    }

    private Path executableResource(String resourcePath, URL resource) {
        Path direct = directPath(resource);
        if (direct != null && Files.isRegularFile(direct)) {
            makeExecutable(direct);
            return direct;
        }
        Path cached = cacheRoot.resolve("current").resolve(platform.platformId()).resolve(platform.executableName());
        try {
            Files.createDirectories(cached.getParent());
            try (InputStream input = resource.openStream()) {
                Files.copy(input, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            makeExecutable(cached);
            return cached;
        } catch (IOException exception) {
            throw new IllegalStateException("无法缓存随包 ripgrep: " + resourcePath + " -> " + cached, exception);
        }
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

    private String mode(Map<String, ?> options) {
        Object option = options == null ? null : options.get(MODE_KEY);
        String value = option == null ? System.getProperty(MODE_KEY) : option.toString();
        if (value == null || value.isBlank()) {
            value = System.getenv("LYPI_TOOL_GREP_RIPGREP_MODE");
        }
        return value == null || value.isBlank() ? "vendor" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
