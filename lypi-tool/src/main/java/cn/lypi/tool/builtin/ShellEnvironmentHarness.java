package cn.lypi.tool.builtin;

import cn.lypi.contracts.runtime.ExecutionResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Builds shell-state file and command plans without executing subprocesses.
 */
public final class ShellEnvironmentHarness {
    static final String ENV_DIR = "env";
    static final String ENV_FILE_VARIABLE = "LYPI_ENV_FILE";
    private static final String SNAPSHOT_PREFIX = "shell-snapshot-";
    private static final Pattern VOLATILE_DIRECTORY_EXPORT = Pattern.compile(
        "^(?:(?:declare|typeset)(?:\\s+-\\S+)*\\s+|export\\s+)(?:PWD|OLDPWD)(?:=|\\s|$).*"
    );

    private final Path stateRoot;

    public ShellEnvironmentHarness(Path stateRoot) {
        this.stateRoot = canonicalIfPresent(
            Objects.requireNonNull(stateRoot, "stateRoot must not be null").toAbsolutePath().normalize()
        );
    }

    public static Path defaultStateRoot() {
        return Path.of(System.getProperty("user.home"), ".lypi", "shell-state");
    }

    public Path stateRoot() {
        return stateRoot;
    }

    record SnapshotPlan(
        String shell,
        Path snapshotFile,
        Path captureFile,
        List<String> command
    ) implements AutoCloseable {
        SnapshotPlan {
            Objects.requireNonNull(shell, "shell must not be null");
            Objects.requireNonNull(snapshotFile, "snapshotFile must not be null");
            Objects.requireNonNull(captureFile, "captureFile must not be null");
            command = List.copyOf(command);
        }

        @Override
        public void close() {
            deleteIfExists(captureFile);
        }
    }

    record CommandPlan(
        String shell,
        Path snapshotFile,
        List<String> command,
        Path cwdCaptureFile,
        List<Path> readOnlyFiles,
        List<Path> writableFiles
    ) implements AutoCloseable {
        CommandPlan {
            Objects.requireNonNull(shell, "shell must not be null");
            Objects.requireNonNull(snapshotFile, "snapshotFile must not be null");
            command = List.copyOf(command);
            Objects.requireNonNull(cwdCaptureFile, "cwdCaptureFile must not be null");
            readOnlyFiles = List.copyOf(readOnlyFiles);
            writableFiles = List.copyOf(writableFiles);
        }

        @Override
        public void close() {
            deleteIfExists(cwdCaptureFile);
        }
    }

    Path sessionDir(Path workspaceRoot, String sessionId) {
        Path workspace = Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null")
            .toAbsolutePath()
            .normalize();
        String rawSessionId = sessionId == null ? "" : sessionId;
        return stateRoot.resolve(sha256(workspace + "\0" + rawSessionId));
    }

    public boolean snapshotExists(Path workspaceRoot, String sessionId, String shell) {
        Path snapshot = snapshotFile(workspaceRoot, sessionId, shell);
        return Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS);
    }

    Optional<SnapshotPlan> prepareSnapshot(Path workspaceRoot, String sessionId, String shell) {
        String canonicalShell = canonicalShell(shell);
        Path snapshot = snapshotFile(workspaceRoot, sessionId, canonicalShell);
        if (Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            Path dir = ensureSessionDir(workspaceRoot, sessionId);
            Path capture = Files.createTempFile(dir, ".snapshot-" + canonicalShell + "-", ".capture");
            return Optional.of(new SnapshotPlan(
                canonicalShell,
                snapshot,
                capture,
                List.of(canonicalShell, "-lc", snapshotCommand(canonicalShell, capture))
            ));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    void completeSnapshot(SnapshotPlan plan, ExecutionResult result) {
        Objects.requireNonNull(plan, "plan must not be null");
        if (result == null || result.exitCode() != 0 || result.timedOut()) {
            deleteIfExists(plan.captureFile());
            return;
        }
        Path capture = plan.captureFile();
        try {
            if (!Files.isRegularFile(capture, LinkOption.NOFOLLOW_LINKS) || Files.size(capture) == 0) {
                deleteIfExists(capture);
                return;
            }
            String filtered = filterSnapshot(Files.readString(capture, StandardCharsets.UTF_8));
            if (filtered.isBlank()) {
                deleteIfExists(capture);
                return;
            }
            Files.writeString(
                capture,
                filtered,
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            atomicReplace(capture, plan.snapshotFile());
        } catch (IOException exception) {
            deleteIfExists(capture);
        }
    }

    CommandPlan prepareCommand(
        Path workspaceRoot,
        String sessionId,
        String shell,
        String command,
        boolean loginShell
    ) throws IOException {
        String canonicalShell = canonicalShell(shell);
        Path dir = ensureSessionDir(workspaceRoot, sessionId);
        Path snapshot = snapshotFile(workspaceRoot, sessionId, canonicalShell);
        boolean useSnapshot = loginShell && Files.isRegularFile(snapshot, LinkOption.NOFOLLOW_LINKS);
        List<Path> scripts = envScripts(workspaceRoot, sessionId);
        Path cwdCapture = Files.createTempFile(dir, ".cwd-", ".capture");
        List<Path> readOnlyFiles = new ArrayList<>();
        StringBuilder wrapped = new StringBuilder();
        if (useSnapshot) {
            readOnlyFiles.add(snapshot);
            wrapped.append(". ").append(shellQuote(snapshot.toString())).append(" 2>/dev/null || true; ");
        }
        for (Path script : scripts) {
            readOnlyFiles.add(script);
            wrapped.append(". ").append(shellQuote(script.toString())).append("; ");
        }
        wrapped.append("eval ").append(shellQuote(Objects.requireNonNull(command, "command must not be null"))).append("; ");
        wrapped.append("lypi_rc=$?; ");
        wrapped.append("pwd -P > ").append(shellQuote(cwdCapture.toString())).append(" 2>/dev/null; ");
        wrapped.append("exit $lypi_rc");
        return new CommandPlan(
            canonicalShell,
            snapshot,
            List.of(canonicalShell, loginShell && !useSnapshot ? "-lc" : "-c", wrapped.toString()),
            cwdCapture,
            readOnlyFiles,
            List.of(cwdCapture)
        );
    }

    Optional<Path> consumeCapturedCwd(CommandPlan plan, Path workspaceRoot, Path previousCwd) {
        Objects.requireNonNull(plan, "plan must not be null");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot must not be null");
        Objects.requireNonNull(previousCwd, "previousCwd must not be null");
        Path capture = plan.cwdCaptureFile();
        try {
            if (!Files.isRegularFile(capture, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String value = stripLineEnding(Files.readString(capture, StandardCharsets.UTF_8));
            if (value.isEmpty() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
                return Optional.empty();
            }
            Path candidate = Path.of(value);
            if (!candidate.isAbsolute()) {
                return Optional.empty();
            }
            Path normalized = candidate.toAbsolutePath().normalize();
            Path realWorkspace = workspaceRoot.toAbsolutePath().normalize().toRealPath();
            Path realCandidate = normalized.toRealPath();
            if (!Files.isDirectory(realCandidate) || !realCandidate.startsWith(realWorkspace)) {
                return Optional.empty();
            }
            return Optional.of(normalized);
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        } finally {
            deleteIfExists(capture);
        }
    }

    public void importEnvFile(Path workspaceRoot, String sessionId, Map<String, String> environment) {
        String source = environment == null ? null : environment.get(ENV_FILE_VARIABLE);
        if (source == null || source.isBlank()) {
            return;
        }
        Path sourceFile;
        try {
            sourceFile = Path.of(source).toAbsolutePath().normalize();
        } catch (RuntimeException exception) {
            return;
        }
        if (!Files.isRegularFile(sourceFile)) {
            return;
        }
        try {
            Path envDir = ensureEnvDir(ensureSessionDir(workspaceRoot, sessionId));
            Path target = envDir.resolve("00-lypi-env-file.sh");
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                Files.copy(sourceFile, target);
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent importer won the create-new race.
            }
        } catch (IOException ignored) {
            // Environment import is optional and must not block command execution.
        }
    }

    List<Path> envScripts(Path workspaceRoot, String sessionId) {
        Path envDir = sessionDir(workspaceRoot, sessionId).resolve(ENV_DIR);
        if (!Files.isDirectory(envDir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        try (var paths = Files.list(envDir)) {
            return paths
                .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .filter(path -> path.getFileName().toString().endsWith(".sh"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        } catch (IOException exception) {
            return List.of();
        }
    }

    private Path ensureSessionDir(Path workspaceRoot, String sessionId) throws IOException {
        Files.createDirectories(stateRoot);
        Path realRoot = stateRoot.toRealPath();
        Path dir = sessionDir(workspaceRoot, sessionId);
        try {
            Files.createDirectory(dir);
        } catch (FileAlreadyExistsException ignored) {
            // Validate the existing path below.
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("shell state session path is not a directory: " + dir);
        }
        Path realDir = dir.toRealPath();
        if (!realDir.startsWith(realRoot)) {
            throw new IOException("shell state session path escapes state root: " + dir);
        }
        return dir;
    }

    private Path ensureEnvDir(Path sessionDir) throws IOException {
        Path envDir = sessionDir.resolve(ENV_DIR);
        try {
            Files.createDirectory(envDir);
        } catch (FileAlreadyExistsException ignored) {
            // Validate the existing path below.
        }
        if (!Files.isDirectory(envDir, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("shell env path is not a directory: " + envDir);
        }
        Path realSessionDir = sessionDir.toRealPath();
        if (!envDir.toRealPath().startsWith(realSessionDir)) {
            throw new IOException("shell env path escapes session directory: " + envDir);
        }
        return envDir;
    }

    private Path snapshotFile(Path workspaceRoot, String sessionId, String shell) {
        String canonicalShell = canonicalShell(shell);
        return sessionDir(workspaceRoot, sessionId).resolve(SNAPSHOT_PREFIX + canonicalShell + ".sh");
    }

    private String snapshotCommand(String shell, Path captureFile) {
        String dump = switch (shell) {
            case "bash" -> "{ export -p; alias -p; declare -f; }";
            case "sh" -> "{ export -p; alias; }";
            case "zsh" -> "{ export -p; alias -L; functions; }";
            default -> throw new IllegalArgumentException("unsupported shell: " + shell);
        };
        return dump + " > " + shellQuote(captureFile.toString()) + " 2>/dev/null";
    }

    private String canonicalShell(String shell) {
        return switch (shell) {
            case "bash", "sh", "zsh" -> shell;
            default -> throw new IllegalArgumentException("shell only supports bash, sh, or zsh");
        };
    }

    private String filterSnapshot(String content) {
        List<String> kept = content.lines()
            .filter(line -> !VOLATILE_DIRECTORY_EXPORT.matcher(line).matches())
            .toList();
        return kept.isEmpty() ? "" : String.join("\n", kept) + "\n";
    }

    private static String stripLineEnding(String value) {
        String stripped = value;
        if (stripped.endsWith("\n")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        if (stripped.endsWith("\r")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return stripped;
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path canonicalIfPresent(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException exception) {
            return path;
        }
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Every invocation uses a unique file, so cleanup is best effort.
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
