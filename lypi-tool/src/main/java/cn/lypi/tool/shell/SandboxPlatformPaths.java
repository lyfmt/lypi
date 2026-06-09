package cn.lypi.tool.shell;

import java.nio.file.Path;
import java.util.List;

/**
 * 提供 Linux Bubblewrap 沙盒的默认平台路径。
 */
final class SandboxPlatformPaths {
    private static final List<Path> DEFAULT_READ_ONLY_PATHS = List.of(
        Path.of("/usr"),
        Path.of("/bin"),
        Path.of("/sbin"),
        Path.of("/lib"),
        Path.of("/lib64"),
        Path.of("/etc"),
        Path.of("/nix/store"),
        Path.of("/run/current-system/sw")
    );

    private SandboxPlatformPaths() {
    }

    /**
     * 返回受限文件系统策略默认只读挂载的系统根。
     *
     * NOTE: 缺失路径由 Bubblewrap `--ro-bind-try` 忽略，因此 Nix/NixOS
     * 根可以安全保留在跨发行版默认值中。
     */
    static List<Path> defaultReadOnlyPaths() {
        return DEFAULT_READ_ONLY_PATHS;
    }
}
