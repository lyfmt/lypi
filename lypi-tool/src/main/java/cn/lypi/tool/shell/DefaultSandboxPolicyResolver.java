package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 生成 Bubblewrap 第一版白名单挂载策略。
 */
public final class DefaultSandboxPolicyResolver implements SandboxPolicyResolver {
    private final SandboxPolicyOptions options;

    public DefaultSandboxPolicyResolver(SandboxPolicyOptions options) {
        this.options = options == null ? SandboxPolicyOptions.defaults() : options;
    }

    @Override
    public SandboxRuntimePolicy resolve(Path workspace, Path cwd) {
        Objects.requireNonNull(cwd, "cwd must not be null");
        Path realWorkspace = realPath(workspace, "workspace");
        realPath(cwd, "cwd");
        return new SandboxRuntimePolicy(
            SandboxPlatformPaths.defaultReadOnlyPaths(),
            List.of(),
            List.of(realWorkspace),
            List.of(),
            options.networkMode(),
            options.failIfUnavailable(),
            options.autoAllowBashIfSandboxed()
        );
    }

    private Path realPath(Path path, String label) {
        Objects.requireNonNull(path, label + " must not be null");
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException(label + " 不存在或不可访问: " + path, exception);
        }
    }
}
