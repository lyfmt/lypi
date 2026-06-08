package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 将执行请求和沙盒策略转换为 Bubblewrap argv。
 */
public final class BubblewrapCommandBuilder {
    private static final List<Path> DEFAULT_READ_ONLY_PATHS = List.of(
        Path.of("/usr"),
        Path.of("/bin"),
        Path.of("/lib"),
        Path.of("/lib64"),
        Path.of("/etc")
    );

    /**
     * 控制 bwrap argv 中的可选运行时挂载。
     */
    public record Options(boolean mountProc) {
        /**
         * 返回默认 bwrap argv 选项。
         */
        public static Options defaults() {
            return new Options(true);
        }
    }

    /**
     * 返回默认 Bubblewrap argv 构建器。
     */
    public static BubblewrapCommandBuilder defaults() {
        return new BubblewrapCommandBuilder();
    }

    /**
     * 构建 bwrap 命令行参数。
     */
    public List<String> build(ExecutionRequest request) {
        return build(request, Options.defaults());
    }

    /**
     * 构建 bwrap 命令行参数。
     */
    public List<String> build(ExecutionRequest request, Options options) {
        Objects.requireNonNull(request, "request must not be null");
        Options safeOptions = options == null ? Options.defaults() : options;
        SandboxRuntimePolicy policy = Objects.requireNonNull(request.sandboxPolicy(), "sandboxPolicy must not be null");
        if (request.command() == null || request.command().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        rejectUnsupportedDenyPaths(policy);
        List<String> argv = new ArrayList<>();
        argv.add("bwrap");
        argv.add("--new-session");
        argv.add("--die-with-parent");
        argv.add("--unshare-user");
        argv.add("--unshare-pid");
        if (policy.networkMode() == NetworkMode.DISABLED) {
            argv.add("--unshare-net");
        }
        for (Path path : readOnlyPaths(policy)) {
            Path mountPath = absoluteNormalized(path, "allowRead");
            argv.add("--ro-bind-try");
            argv.add(mountPath.toString());
            argv.add(mountPath.toString());
        }
        argv.add("--dev");
        argv.add("/dev");
        if (safeOptions.mountProc()) {
            argv.add("--proc");
            argv.add("/proc");
        }
        argv.add("--tmpfs");
        argv.add("/tmp");
        for (Path path : writablePaths(policy, request.cwd())) {
            Path mountPath = absoluteNormalized(path, "allowWrite");
            argv.add("--bind");
            argv.add(mountPath.toString());
            argv.add(mountPath.toString());
        }
        if (request.cwd() != null) {
            Path cwd = absoluteNormalized(request.cwd(), "cwd");
            argv.add("--chdir");
            argv.add(cwd.toString());
        }
        argv.add("--");
        argv.addAll(request.command());
        return List.copyOf(argv);
    }

    private List<Path> readOnlyPaths(SandboxRuntimePolicy policy) {
        return policy.allowRead().isEmpty() ? DEFAULT_READ_ONLY_PATHS : policy.allowRead();
    }

    private List<Path> writablePaths(SandboxRuntimePolicy policy, Path cwd) {
        if (!policy.allowWrite().isEmpty()) {
            return policy.allowWrite();
        }
        return cwd == null ? List.of() : List.of(cwd);
    }

    private void rejectUnsupportedDenyPaths(SandboxRuntimePolicy policy) {
        if (!policy.denyRead().isEmpty() || !policy.denyWrite().isEmpty()) {
            throw new IllegalArgumentException("denyRead/denyWrite are unsupported by bubblewrap v1 policy builder");
        }
    }

    private Path absoluteNormalized(Path path, String label) {
        Objects.requireNonNull(path, label + " path must not be null");
        Path normalized = path.normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException(label + " path must be absolute: " + path);
        }
        return normalized;
    }
}
