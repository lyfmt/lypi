package cn.lypi.tool.shell;

import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * 将执行请求和沙盒策略转换为 Bubblewrap argv。
 */
public final class BubblewrapCommandBuilder {
    private static final String EMPTY_FILE_FD = "0";
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
     * Bubblewrap argv 和需要在执行后清理的合成挂载目标。
     */
    public record BuildResult(
        List<String> argv,
        List<SyntheticMountTarget> syntheticMountTargets,
        List<ProtectedCreateTarget> protectedCreateTargets
    ) {
        public BuildResult {
            argv = List.copyOf(argv);
            syntheticMountTargets = List.copyOf(syntheticMountTargets);
            protectedCreateTargets = List.copyOf(protectedCreateTargets);
        }
    }

    /**
     * 记录 bwrap 为缺失路径合成的宿主挂载目标。
     */
    public record SyntheticMountTarget(Path path, Kind kind, boolean preservesPreExistingPath, Object preExistingFileKey) {
        public SyntheticMountTarget {
            Objects.requireNonNull(path, "path must not be null");
            Objects.requireNonNull(kind, "kind must not be null");
        }

        public SyntheticMountTarget(Path path, Kind kind) {
            this(path, kind, false, null);
        }

        /**
         * 返回合成空文件目标。
         */
        public static SyntheticMountTarget emptyFile(Path path) {
            return new SyntheticMountTarget(path, Kind.EMPTY_FILE);
        }

        /**
         * 返回合成空目录目标。
         */
        public static SyntheticMountTarget emptyDirectory(Path path) {
            return new SyntheticMountTarget(path, Kind.EMPTY_DIRECTORY);
        }

        /**
         * 返回已存在空文件的合成遮蔽目标。
         */
        public static SyntheticMountTarget existingEmptyFile(Path path, BasicFileAttributes attributes) {
            return new SyntheticMountTarget(path, Kind.EMPTY_FILE, true, attributes.fileKey());
        }

        /**
         * 返回已存在空目录的合成遮蔽目标。
         */
        public static SyntheticMountTarget existingEmptyDirectory(Path path, BasicFileAttributes attributes) {
            return new SyntheticMountTarget(path, Kind.EMPTY_DIRECTORY, true, attributes.fileKey());
        }

        boolean shouldRemoveAfter(BasicFileAttributes attributes) {
            if (kind == Kind.EMPTY_FILE && (!attributes.isRegularFile() || attributes.size() != 0)) {
                return false;
            }
            if (kind == Kind.EMPTY_DIRECTORY && !attributes.isDirectory()) {
                return false;
            }
            if (!preservesPreExistingPath) {
                return true;
            }
            return preExistingFileKey != null && !preExistingFileKey.equals(attributes.fileKey());
        }

        /**
         * 标识合成目标的预期文件类型。
         */
        public enum Kind {
            EMPTY_FILE,
            EMPTY_DIRECTORY
        }
    }

    /**
     * 记录不应被沙盒创建的宿主路径。
     */
    public record ProtectedCreateTarget(Path path) {
        public ProtectedCreateTarget {
            Objects.requireNonNull(path, "path must not be null");
        }

        /**
         * 返回缺失路径的受保护创建目标。
         */
        public static ProtectedCreateTarget missing(Path path) {
            return new ProtectedCreateTarget(path);
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
        return buildDetailed(request, Options.defaults()).argv();
    }

    /**
     * 构建 bwrap 命令行参数。
     */
    public List<String> build(ExecutionRequest request, Options options) {
        return buildDetailed(request, options).argv();
    }

    /**
     * 构建 bwrap 命令行参数和执行后清理信息。
     */
    public BuildResult buildDetailed(ExecutionRequest request, Options options) {
        Objects.requireNonNull(request, "request must not be null");
        Options safeOptions = options == null ? Options.defaults() : options;
        SandboxRuntimePolicy policy = Objects.requireNonNull(request.sandboxPolicy(), "sandboxPolicy must not be null");
        if (request.command() == null || request.command().isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        rejectUnsupportedDenyWrite(policy);
        List<String> argv = new ArrayList<>();
        List<SyntheticMountTarget> syntheticMountTargets = new ArrayList<>();
        List<ProtectedCreateTarget> protectedCreateTargets = new ArrayList<>();
        argv.add("bwrap");
        argv.add("--new-session");
        argv.add("--die-with-parent");
        argv.add("--unshare-user");
        argv.add("--unshare-pid");
        if (policy.networkMode() == NetworkMode.DISABLED) {
            argv.add("--unshare-net");
        }
        argv.add("--tmpfs");
        argv.add("/");
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
        appendProtectedMetadataMasks(argv, writableMountPaths, syntheticMountTargets, protectedCreateTargets);
        appendDenyReadMasks(argv, policy.denyRead(), writableMountPaths, request.cwd(), syntheticMountTargets, protectedCreateTargets);
        if (request.cwd() != null) {
            Path cwd = absoluteNormalized(request.cwd(), "cwd");
            argv.add("--chdir");
            argv.add(cwd.toString());
        }
        argv.add("--");
        argv.addAll(request.command());
        return new BuildResult(argv, syntheticMountTargets, protectedCreateTargets);
    }

    private List<Path> readOnlyPaths(SandboxRuntimePolicy policy) {
        return policy.allowRead().isEmpty() ? SandboxPlatformPaths.defaultReadOnlyPaths() : policy.allowRead();
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

    private void appendDenyReadMasks(
        List<String> argv,
        List<Path> denyReadPaths,
        List<Path> writableRoots,
        Path cwd,
        List<SyntheticMountTarget> syntheticMountTargets,
        List<ProtectedCreateTarget> protectedCreateTargets
    ) {
        Path normalizedCwd = cwd == null ? null : absoluteNormalized(cwd, "cwd");
        LinkedHashSet<Path> masks = new LinkedHashSet<>();
        for (Path path : denyReadPaths) {
            Path denyReadPath = absoluteNormalized(path, "denyRead");
            Path maskPath = denyReadMaskPath(denyReadPath, writableRoots);
            validateDenyReadPath(maskPath, writableRoots, normalizedCwd);
            masks.add(maskPath);
        }
        rejectNestedDenyReadMasks(masks);
        for (Path denyReadPath : masks) {
            appendDenyReadMask(argv, denyReadPath, writableRoots, syntheticMountTargets, protectedCreateTargets);
        }
    }

    private Path denyReadMaskPath(Path denyReadPath, List<Path> writableRoots) {
        rejectSymlinkPath(denyReadPath, "denyRead path must not cross a symbolic link");
        if (Files.exists(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            return denyReadPath;
        }
        Path firstMissingComponent = firstMissingComponent(denyReadPath);
        if (firstMissingComponent != null && isWithinWritableRoots(firstMissingComponent, writableRoots)) {
            return firstMissingComponent;
        }
        throw new IllegalArgumentException("missing denyRead path is unsupported by bubblewrap v1 policy builder: " + denyReadPath);
    }

    private void validateDenyReadPath(Path denyReadPath, List<Path> writableRoots, Path cwd) {
        rejectSymlinkPath(denyReadPath, "denyRead path must not cross a symbolic link");
        if (!Files.exists(denyReadPath, LinkOption.NOFOLLOW_LINKS)) {
            return;
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

    private void appendDenyReadMask(
        List<String> argv,
        Path denyReadPath,
        List<Path> writableRoots,
        List<SyntheticMountTarget> syntheticMountTargets,
        List<ProtectedCreateTarget> protectedCreateTargets
    ) {
        List<Path> writableDescendants = writableDescendantsOf(denyReadPath, writableRoots);
        boolean missingPath = !Files.exists(denyReadPath, LinkOption.NOFOLLOW_LINKS);
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
            appendProtectedMetadataMasks(argv, writableDescendants, syntheticMountTargets, protectedCreateTargets);
            return;
        }
        if (!writableDescendants.isEmpty()) {
            throw new IllegalArgumentException(
                "denyRead file ancestor of allowWrite is unsupported by bubblewrap v1 policy builder: " + denyReadPath
            );
        }
        argv.add("--ro-bind-data");
        // NOTE: BubblewrapExecutor 通过 HostExecutor 执行 bwrap；HostExecutor 将子进程
        // stdin 重定向到 /dev/null，因此 bwrap 从 fd 0 读取 EOF 并生成空只读文件。
        argv.add(EMPTY_FILE_FD);
        argv.add(denyReadPath.toString());
        if (missingPath) {
            syntheticMountTargets.add(SyntheticMountTarget.emptyFile(denyReadPath));
        }
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

    private void appendReadonlyEmptyDirectoryMask(List<String> argv, Path path) {
        argv.add("--perms");
        argv.add("555");
        argv.add("--tmpfs");
        argv.add(path.toString());
        argv.add("--remount-ro");
        argv.add(path.toString());
    }

    private Path firstMissingComponent(Path path) {
        Path current = path.getRoot();
        if (current == null) {
            current = Path.of("");
        }
        for (Path component : path) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return current;
            }
        }
        return null;
    }

    private boolean isWithinWritableRoots(Path path, List<Path> writableRoots) {
        for (Path writableRoot : writableRoots) {
            if (path.startsWith(writableRoot)) {
                return true;
            }
        }
        return false;
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

    private void appendProtectedMetadataMasks(
        List<String> argv,
        List<Path> writableRoots,
        List<SyntheticMountTarget> syntheticMountTargets,
        List<ProtectedCreateTarget> protectedCreateTargets
    ) {
        LinkedHashSet<Path> protectedMetadataPaths = new LinkedHashSet<>();
        LinkedHashSet<Path> protectedCreatePaths = new LinkedHashSet<>();
        for (Path writableRoot : writableRoots) {
            if (isProtectedMetadataPath(writableRoot)) {
                protectedMetadataPaths.add(writableRoot);
            }
            if (!Files.isDirectory(writableRoot, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            for (String name : PROTECTED_METADATA_NAMES) {
                Path protectedMetadataPath = writableRoot.resolve(name).normalize();
                if (shouldLeaveMissingGitForParentRepoDiscovery(writableRoot, name)) {
                    protectedCreatePaths.add(protectedMetadataPath);
                } else {
                    protectedMetadataPaths.add(protectedMetadataPath);
                }
            }
        }
        protectedCreatePaths.removeAll(protectedMetadataPaths);
        for (Path protectedMetadataPath : protectedMetadataPaths) {
            appendProtectedMetadataMask(argv, protectedMetadataPath, syntheticMountTargets);
        }
        for (Path protectedCreatePath : protectedCreatePaths) {
            appendProtectedCreateTarget(protectedCreateTargets, protectedCreatePath);
        }
    }

    private void appendProtectedCreateTarget(List<ProtectedCreateTarget> protectedCreateTargets, Path path) {
        for (ProtectedCreateTarget target : protectedCreateTargets) {
            if (target.path().equals(path)) {
                return;
            }
        }
        protectedCreateTargets.add(ProtectedCreateTarget.missing(path));
    }

    private void appendProtectedMetadataMask(List<String> argv, Path path, List<SyntheticMountTarget> syntheticMountTargets) {
        if (Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("protected metadata path must not be a symbolic link: " + path);
        }
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes attributes = readAttributes(path);
            if (attributes.isRegularFile() && attributes.size() == 0) {
                appendReadonlyEmptyFileMask(argv, path);
                syntheticMountTargets.add(SyntheticMountTarget.existingEmptyFile(path, attributes));
                return;
            }
            if (attributes.isDirectory() && directoryIsEmpty(path)) {
                appendReadonlyEmptyDirectoryMask(argv, path);
                syntheticMountTargets.add(SyntheticMountTarget.existingEmptyDirectory(path, attributes));
                return;
            }
            appendReadonlyBind(argv, path);
            return;
        }
        appendReadonlyEmptyDirectoryMask(argv, path);
        syntheticMountTargets.add(SyntheticMountTarget.emptyDirectory(path));
    }

    private void appendReadonlyEmptyFileMask(List<String> argv, Path path) {
        argv.add("--ro-bind-data");
        argv.add(EMPTY_FILE_FD);
        argv.add(path.toString());
    }

    private BasicFileAttributes readAttributes(Path path) {
        try {
            return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalArgumentException("protected metadata path cannot be inspected: " + path, exception);
        }
    }

    private boolean directoryIsEmpty(Path path) {
        try (java.util.stream.Stream<Path> entries = Files.list(path)) {
            return entries.findAny().isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean shouldLeaveMissingGitForParentRepoDiscovery(Path writableRoot, String name) {
        Path gitPath = writableRoot.resolve(name).normalize();
        return ".git".equals(name)
            && !Files.exists(gitPath, LinkOption.NOFOLLOW_LINKS)
            && ancestorHasGitMetadata(writableRoot);
    }

    private boolean ancestorHasGitMetadata(Path path) {
        Path ancestor = path.getParent();
        while (ancestor != null) {
            if (hasGitMetadata(ancestor.resolve(".git"))) {
                return true;
            }
            ancestor = ancestor.getParent();
        }
        return false;
    }

    private boolean hasGitMetadata(Path gitPath) {
        if (Files.isDirectory(gitPath, LinkOption.NOFOLLOW_LINKS)) {
            return Files.exists(gitPath.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS);
        }
        if (!Files.isRegularFile(gitPath, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return Files.readString(gitPath).trim().startsWith("gitdir:");
        } catch (IOException exception) {
            return false;
        }
    }

    private boolean isProtectedMetadataPath(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && PROTECTED_METADATA_NAMES.contains(fileName.toString());
    }

    private Path absoluteNormalized(Path path, String label) {
        Objects.requireNonNull(path, label + " path must not be null");
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(label + " path must be absolute: " + path);
        }
        Path normalized = path.normalize();
        if (!path.equals(normalized)) {
            throw new IllegalArgumentException(label + " path must be normalized: " + path);
        }
        return normalized;
    }
}
