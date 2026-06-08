package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 将执行请求和沙盒策略转换为 Bubblewrap argv。
 */
public final class BubblewrapCommandBuilder {
    private static final String EMPTY_FILE_FD = "0";
    private static final List<Path> DEFAULT_READ_ONLY_PATHS = List.of(
        Path.of("/usr"),
        Path.of("/bin"),
        Path.of("/lib"),
        Path.of("/lib64"),
        Path.of("/etc")
    );
    private static final List<String> PROTECTED_METADATA_NAMES = List.of(".git", ".codex", ".agents");

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
        rejectUnsupportedDenyWrite(policy);
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
        List<Path> writableMountPaths = new ArrayList<>();
        for (Path path : writablePaths(policy, request.cwd())) {
            Path mountPath = absoluteNormalized(path, "allowWrite");
            rejectSymlinkPath(mountPath, "allowWrite path must not cross a symbolic link");
            writableMountPaths.add(mountPath);
            appendWritableBind(argv, mountPath);
        }
        for (Path protectedMetadataPath : existingProtectedMetadataPaths(writableMountPaths)) {
            appendReadonlyBind(argv, protectedMetadataPath);
        }
        appendDenyReadMasks(argv, policy.denyRead(), writableMountPaths, request.cwd());
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

    private void rejectUnsupportedDenyWrite(SandboxRuntimePolicy policy) {
        if (!policy.denyWrite().isEmpty()) {
            throw new IllegalArgumentException("denyWrite is unsupported by bubblewrap v1 policy builder");
        }
    }

    private void appendDenyReadMasks(List<String> argv, List<Path> denyReadPaths, List<Path> writableRoots, Path cwd) {
        Path normalizedCwd = cwd == null ? null : absoluteNormalized(cwd, "cwd");
        LinkedHashSet<Path> masks = new LinkedHashSet<>();
        for (Path path : denyReadPaths) {
            Path denyReadPath = absoluteNormalized(path, "denyRead");
            validateDenyReadPath(denyReadPath, writableRoots, normalizedCwd);
            masks.add(denyReadPath);
        }
        rejectNestedDenyReadMasks(masks);
        for (Path denyReadPath : masks) {
            appendDenyReadMask(argv, denyReadPath, writableRoots);
        }
    }

    private void validateDenyReadPath(Path denyReadPath, List<Path> writableRoots, Path cwd) {
        rejectSymlinkPath(denyReadPath, "denyRead path must not cross a symbolic link");
        if (!Files.exists(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("missing denyRead path is unsupported by bubblewrap v1 policy builder: " + denyReadPath);
        }
        if (!Files.isDirectory(denyReadPath, LinkOption.NOFOLLOW_LINKS)
            && !Files.isRegularFile(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("denyRead path type is unsupported by bubblewrap v1 policy builder: " + denyReadPath);
        }
        if (Files.isRegularFile(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            for (Path writableRoot : writableRoots) {
                if (writableRoot.startsWith(denyReadPath) && !writableRoot.equals(denyReadPath)) {
                    throw new IllegalArgumentException(
                        "denyRead file ancestor of allowWrite is unsupported by bubblewrap v1 policy builder: " + denyReadPath
                    );
                }
            }
        }
        if (cwd != null && cwd.startsWith(denyReadPath) && !cwdInsideWritableDescendant(cwd, denyReadPath, writableRoots)) {
            throw new IllegalArgumentException("cwd inside denyRead is unsupported by bubblewrap v1 policy builder: " + cwd);
        }
    }

    private boolean cwdInsideWritableDescendant(Path cwd, Path denyReadPath, List<Path> writableRoots) {
        for (Path writableRoot : writableRoots) {
            if (writableRoot.startsWith(denyReadPath)
                && !writableRoot.equals(denyReadPath)
                && Files.isDirectory(writableRoot, LinkOption.NOFOLLOW_LINKS)
                && cwd.startsWith(writableRoot)) {
                return true;
            }
        }
        return false;
    }

    private void appendDenyReadMask(List<String> argv, Path denyReadPath, List<Path> writableRoots) {
        List<Path> writableDescendants = writableDescendantsOf(denyReadPath, writableRoots);
        argv.add("--perms");
        argv.add(writableDescendants.isEmpty() ? "000" : "111");
        if (Files.isDirectory(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            argv.add("--tmpfs");
            argv.add(denyReadPath.toString());
            for (Path writableDescendant : writableDescendants) {
                appendMountTargetDirs(argv, writableDescendant, denyReadPath);
                appendMountTargetFile(argv, writableDescendant);
            }
            argv.add("--remount-ro");
            argv.add(denyReadPath.toString());
            for (Path writableDescendant : writableDescendants) {
                appendWritableBind(argv, writableDescendant);
            }
            for (Path protectedMetadataPath : existingProtectedMetadataPaths(writableDescendants)) {
                appendReadonlyBind(argv, protectedMetadataPath);
            }
            return;
        }
        if (!writableDescendants.isEmpty()) {
            throw new IllegalArgumentException(
                "denyRead file ancestor of allowWrite is unsupported by bubblewrap v1 policy builder: " + denyReadPath
            );
        }
        argv.add("--ro-bind-data");
        // NOTE: BubblewrapExecutor 通过 HostExecutor 执行 bwrap；HostExecutor 会关闭子进程 stdin，
        // 因此 bwrap 从 fd 0 读取 EOF，并把 denyRead 文件覆盖成空且不可读的文件。
        argv.add(EMPTY_FILE_FD);
        argv.add(denyReadPath.toString());
    }

    private List<Path> writableDescendantsOf(Path denyReadPath, List<Path> writableRoots) {
        List<Path> descendants = new ArrayList<>();
        for (Path writableRoot : writableRoots) {
            if (writableRoot.startsWith(denyReadPath) && !writableRoot.equals(denyReadPath)) {
                descendants.add(writableRoot);
            }
        }
        descendants.sort((left, right) -> Integer.compare(pathDepth(left), pathDepth(right)));
        return List.copyOf(descendants);
    }

    private void appendMountTargetDirs(List<String> argv, Path mountTarget, Path anchor) {
        Path lastDirectory = Files.isDirectory(mountTarget, LinkOption.NOFOLLOW_LINKS)
            ? mountTarget
            : mountTarget.getParent();
        if (lastDirectory == null || !lastDirectory.startsWith(anchor) || lastDirectory.equals(anchor)) {
            return;
        }
        List<Path> directories = new ArrayList<>();
        Path current = lastDirectory;
        while (current != null && !current.equals(anchor)) {
            directories.add(current);
            current = current.getParent();
        }
        java.util.Collections.reverse(directories);
        for (Path directory : directories) {
            argv.add("--dir");
            argv.add(directory.toString());
        }
    }

    private void appendMountTargetFile(List<String> argv, Path mountTarget) {
        if (!Files.isRegularFile(mountTarget, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        argv.add("--file");
        argv.add(EMPTY_FILE_FD);
        argv.add(mountTarget.toString());
    }

    private int pathDepth(Path path) {
        return path.getNameCount();
    }

    private void appendWritableBind(List<String> argv, Path path) {
        argv.add("--bind");
        argv.add(path.toString());
        argv.add(path.toString());
    }

    private void appendReadonlyBind(List<String> argv, Path path) {
        argv.add("--ro-bind");
        argv.add(path.toString());
        argv.add(path.toString());
    }

    private void rejectNestedDenyReadMasks(LinkedHashSet<Path> denyReadMasks) {
        List<Path> masks = List.copyOf(denyReadMasks);
        for (int left = 0; left < masks.size(); left++) {
            for (int right = left + 1; right < masks.size(); right++) {
                Path leftPath = masks.get(left);
                Path rightPath = masks.get(right);
                if (leftPath.startsWith(rightPath) || rightPath.startsWith(leftPath)) {
                    throw new IllegalArgumentException(
                        "nested denyRead paths are unsupported by bubblewrap v1 policy builder: " + leftPath + " and " + rightPath
                    );
                }
            }
        }
    }

    private void rejectSymlinkPath(Path path, String message) {
        Path current = path.getRoot();
        if (current == null) {
            current = Path.of("");
        }
        for (Path component : path) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current)) {
                throw new IllegalArgumentException(
                    message + ": " + current
                );
            }
        }
    }

    private List<Path> existingProtectedMetadataPaths(List<Path> writableRoots) {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        for (Path writableRoot : writableRoots) {
            if (isProtectedMetadataPath(writableRoot) && protectedMetadataPathExists(writableRoot)) {
                paths.add(writableRoot);
            }
            for (String name : PROTECTED_METADATA_NAMES) {
                Path path = writableRoot.resolve(name).normalize();
                if (protectedMetadataPathExists(path)) {
                    paths.add(path);
                }
            }
        }
        return List.copyOf(paths);
    }

    private boolean protectedMetadataPathExists(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("protected metadata path must not be a symbolic link: " + path);
        }
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isProtectedMetadataPath(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && PROTECTED_METADATA_NAMES.contains(fileName.toString());
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
