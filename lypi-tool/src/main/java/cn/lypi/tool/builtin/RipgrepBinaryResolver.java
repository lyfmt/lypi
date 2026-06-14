package cn.lypi.tool.builtin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

final class RipgrepBinaryResolver {
    static final String MODE_KEY = "lypi.tool.grep.ripgrep.mode";

    private final RipgrepPlatform platform;
    private final Path resourceRoot;

    private RipgrepBinaryResolver(RipgrepPlatform platform, Path resourceRoot) {
        this.platform = Objects.requireNonNull(platform, "platform must not be null");
        this.resourceRoot = Objects.requireNonNull(resourceRoot, "resourceRoot must not be null");
    }

    static RipgrepBinaryResolver defaults() {
        return new RipgrepBinaryResolver(RipgrepPlatform.current(), Path.of("lypi-tool", "src", "main", "resources"));
    }

    static RipgrepBinaryResolver forTesting(RipgrepPlatform platform, Path resourceRoot) {
        return new RipgrepBinaryResolver(platform, resourceRoot);
    }

    RipgrepBinary resolve(Map<String, ?> options) {
        String mode = mode(options);
        if ("system".equals(mode)) {
            return new RipgrepBinary("rg", "system");
        }
        Path binary = resourceRoot.resolve(platform.resourcePath()).normalize();
        if (Files.isRegularFile(binary) && Files.isExecutable(binary)) {
            return new RipgrepBinary(binary.toString(), "vendor");
        }
        throw new IllegalStateException("未找到随包 ripgrep: " + platform.platformId()
            + "，可临时设置 " + MODE_KEY + "=system 使用系统 rg。");
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
