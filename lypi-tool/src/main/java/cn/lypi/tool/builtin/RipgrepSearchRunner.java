package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class RipgrepSearchRunner {
    private static final AbortSignal NOT_ABORTED = () -> false;

    private final Executor executor;
    private final RipgrepCommandBuilder commandBuilder;
    private final RipgrepBinaryResolver binaryResolver;
    private final Duration timeout;

    RipgrepSearchRunner(
        Executor executor,
        RipgrepCommandBuilder commandBuilder,
        RipgrepBinaryResolver binaryResolver,
        Duration timeout
    ) {
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.commandBuilder = Objects.requireNonNull(commandBuilder, "commandBuilder must not be null");
        this.binaryResolver = Objects.requireNonNull(binaryResolver, "binaryResolver must not be null");
        this.timeout = timeout == null ? Duration.ofSeconds(20) : timeout;
    }

    RipgrepSearchResult search(GrepQuery query, Path searchRoot, ToolUseContext context, ProgressSink progress) {
        RipgrepBinary binary;
        try {
            binary = binaryResolver.resolve(context == null ? Map.of() : context.metadata());
        } catch (RuntimeException exception) {
            return RipgrepSearchResult.error(exception.getMessage());
        }
        List<String> command = new ArrayList<>();
        command.add(binary.command());
        command.addAll(commandBuilder.build(query));
        ExecutionRequest request = new ExecutionRequest(
            command,
            searchRoot,
            Map.of(),
            timeout,
            readOnlyPolicy(searchRoot, binary)
        );
        ExecutionResult result = executor.execute(request, progress, abortSignal(context));
        if (result.timedOut()) {
            return RipgrepSearchResult.error("搜索超时，请缩小 path 或 pattern。");
        }
        if (result.exitCode() == 0) {
            return RipgrepSearchResult.success(lines(result.stdout()));
        }
        if (result.exitCode() == 1) {
            return RipgrepSearchResult.success(List.of());
        }
        String message = result.stderr().isBlank() ? "ripgrep 执行失败，exitCode=" + result.exitCode() : result.stderr();
        return RipgrepSearchResult.error(message);
    }

    private SandboxRuntimePolicy readOnlyPolicy(Path searchRoot, RipgrepBinary binary) {
        return new SandboxRuntimePolicy(
            readOnlyPaths(searchRoot, binary),
            List.of(),
            List.of(),
            List.of(),
            NetworkMode.DISABLED,
            false,
            true
        );
    }

    private List<Path> readOnlyPaths(Path searchRoot, RipgrepBinary binary) {
        List<Path> paths = new ArrayList<>();
        paths.add(Path.of("/usr"));
        paths.add(Path.of("/bin"));
        paths.add(Path.of("/sbin"));
        paths.add(Path.of("/lib"));
        paths.add(Path.of("/lib64"));
        paths.add(Path.of("/etc"));
        paths.add(Path.of("/nix/store"));
        paths.add(Path.of("/run/current-system/sw"));
        paths.add(searchRoot);
        Path binaryParent = binaryParent(binary);
        if (binaryParent != null) {
            paths.add(binaryParent);
        }
        return List.copyOf(paths);
    }

    private Path binaryParent(RipgrepBinary binary) {
        if (binary == null || binary.command() == null || binary.command().isBlank() || "system".equals(binary.mode())) {
            return null;
        }
        Path command = Path.of(binary.command());
        Path parent = command.getParent();
        return parent == null ? null : parent.toAbsolutePath().normalize();
    }

    private AbortSignal abortSignal(ToolUseContext context) {
        if (context == null) {
            return NOT_ABORTED;
        }
        Object value = context.metadata().get("abortSignal");
        return value instanceof AbortSignal signal ? signal : NOT_ABORTED;
    }

    private List<String> lines(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : stdout.split("\\R")) {
            String normalized = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (!normalized.isEmpty()) {
                lines.add(normalized);
            }
        }
        return List.copyOf(lines);
    }
}
