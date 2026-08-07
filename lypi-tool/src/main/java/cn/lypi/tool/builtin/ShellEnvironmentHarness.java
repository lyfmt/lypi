package cn.lypi.tool.builtin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 会话级 shell 环境 harness：snapshot 重放 + session env 脚本 + cwd 捕获。
 *
 * NOTE: 对齐 Claude Code 的一次性进程模型——每条命令仍是独立进程，
 * 状态通过外部文件（snapshot / env/*.sh / cwd 文件）跨命令继承，不做常驻 shell。
 */
public final class ShellEnvironmentHarness {
    static final String SNAPSHOT_FILE = "shell-snapshot.sh";
    static final String CWD_FILE = "cwd";
    static final String ENV_DIR = "env";
    static final String ENV_FILE_VARIABLE = "LYPI_ENV_FILE";
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(10);

    private final Path stateRoot;

    public ShellEnvironmentHarness(Path stateRoot) {
        this.stateRoot = Objects.requireNonNull(stateRoot, "stateRoot must not be null").toAbsolutePath().normalize();
    }

    /**
     * 默认状态根目录：~/.lypi/shell-state。
     */
    public static Path defaultStateRoot() {
        return Path.of(System.getProperty("user.home"), ".lypi", "shell-state");
    }

    Path sessionDir(String sessionId) {
        String safeId = sessionId == null || sessionId.isBlank() ? "anonymous" : sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return stateRoot.resolve(safeId);
    }

    /**
     * snapshot 是否已存在（存在则调用方可用非 login shell）。
     */
    public boolean snapshotExists(String sessionId) {
        return Files.isRegularFile(sessionDir(sessionId).resolve(SNAPSHOT_FILE));
    }

    /**
     * 用 login shell dump 当前环境为 snapshot 文件（export -p / alias -p / declare -f）。
     *
     * 已存在或生成失败时静默跳过——失败只意味着下次命令回退 login shell。
     */
    public void ensureSnapshot(String sessionId, String shell) {
        Path snapshot = sessionDir(sessionId).resolve(SNAPSHOT_FILE);
        if (Files.exists(snapshot)) {
            return;
        }
        try {
            Files.createDirectories(snapshot.getParent());
            Path tmp = snapshot.resolveSibling(SNAPSHOT_FILE + ".tmp");
            Process process = new ProcessBuilder(
                shell,
                "-lc",
                "{ export -p; alias -p; declare -f; } > " + shellQuote(tmp.toString()) + " 2>/dev/null"
            ).redirectErrorStream(false).start();
            boolean exited = process.waitFor(SNAPSHOT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                Files.deleteIfExists(tmp);
                return;
            }
            if (process.exitValue() == 0 && Files.exists(tmp) && Files.size(tmp) > 0) {
                Files.move(tmp, snapshot, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // snapshot 是优化项，失败不阻塞命令执行
        }
    }

    /**
     * 包装用户命令：source snapshot + session env 脚本 + eval 用户命令 + 捕获最终 cwd。
     *
     * 结构（对齐 CC）：
     *   source snapshot || true && source env/*.sh && eval '<command>' ; rc=$?; pwd -P >| cwd ; exit $rc
     *
     * eval 让 snapshot 中定义的 alias 在二次解析时生效；cwd 捕获用 `;` 而非 `&&`，命令失败也记录。
     */
    public String wrap(String sessionId, String command) {
        StringBuilder builder = new StringBuilder();
        Path dir = sessionDir(sessionId);
        Path snapshot = dir.resolve(SNAPSHOT_FILE);
        if (Files.isRegularFile(snapshot)) {
            builder.append("source ").append(shellQuote(snapshot.toString())).append(" 2>/dev/null || true && ");
        }
        builder.append("lypi_env_dir=").append(shellQuote(dir.resolve(ENV_DIR).toString())).append("; ");
        builder.append("if [ -d \"$lypi_env_dir\" ]; then ");
        builder.append("for lypi_env_file in \"$lypi_env_dir\"/*.sh; do [ -f \"$lypi_env_file\" ] && source \"$lypi_env_file\"; done; ");
        builder.append("fi; ");
        builder.append("unset lypi_env_dir; ");
        builder.append("eval ").append(shellQuote(command)).append("; ");
        builder.append("lypi_rc=$?; ");
        builder.append("pwd -P >| ").append(shellQuote(dir.resolve(CWD_FILE).toString())).append(" 2>/dev/null; ");
        builder.append("exit $lypi_rc");
        return builder.toString();
    }

    /**
     * 读取并清除上一条命令捕获的 cwd。
     */
    public Optional<Path> consumeCapturedCwd(String sessionId) {
        Path file = sessionDir(sessionId).resolve(CWD_FILE);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String content = Files.readString(file, StandardCharsets.UTF_8).trim();
            Files.deleteIfExists(file);
            if (content.isEmpty()) {
                return Optional.empty();
            }
            Path cwd = Path.of(content);
            // cwd 可能刚被命令删掉；此时不回写，调用方保留旧值（或回退启动目录）
            return Files.isDirectory(cwd) ? Optional.of(cwd) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * 外部 runner 注入的 env 脚本（LYPI_ENV_FILE 指向的文件复制进 session env 目录）。
     */
    public void importEnvFile(String sessionId, Map<String, String> environment) {
        String source = environment == null ? null : environment.get(ENV_FILE_VARIABLE);
        if (source == null || source.isBlank()) {
            return;
        }
        Path sourceFile = Path.of(source);
        if (!Files.isRegularFile(sourceFile)) {
            return;
        }
        try {
            Path envDir = sessionDir(sessionId).resolve(ENV_DIR);
            Files.createDirectories(envDir);
            Path target = envDir.resolve("00-lypi-env-file.sh");
            if (!Files.exists(target)) {
                Files.copy(sourceFile, target);
            }
        } catch (IOException e) {
            // 注入失败不阻塞执行
        }
    }

    /**
     * session env 目录下按文件名排序的脚本列表（测试与诊断用）。
     */
    Stream<Path> envScripts(String sessionId) {
        Path envDir = sessionDir(sessionId).resolve(ENV_DIR);
        if (!Files.isDirectory(envDir)) {
            return Stream.empty();
        }
        try {
            return Files.list(envDir)
                .filter(path -> path.getFileName().toString().endsWith(".sh"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()));
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
