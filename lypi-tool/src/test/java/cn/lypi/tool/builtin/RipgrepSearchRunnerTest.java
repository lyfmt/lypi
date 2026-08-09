package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.tool.ToolUseContext;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RipgrepSearchRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void exitZeroReturnsStdoutLinesAndBuildsExecutionRequest() throws Exception {
        Path binary = vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
            0,
            "a.java\nb.java\r\n",
            "",
            false,
            Optional.empty()
        ));
        RipgrepSearchRunner runner = runner(executor);
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));

        RipgrepSearchResult result = runner.search(query, tempDir, context(), message -> {
        });

        assertFalse(result.isError());
        assertEquals(List.of("a.java", "b.java"), result.lines());
        assertEquals(binary.toString(), executor.request.command().getFirst());
        assertEquals(tempDir, executor.request.cwd());
        assertEquals(Duration.ofSeconds(20), executor.request.timeout());
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(tempDir));
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(Path.of("/bin")));
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(binary.getParent()));
    }

    @Test
    void fileSearchUsesParentDirectoryAndAbsoluteTargetOperand() throws Exception {
        Path binary = vendorBinary();
        Path nested = Files.createDirectories(tempDir.resolve("src/pkg"));
        Path file = Files.writeString(nested.resolve("sample.py"), "needle\n");
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "needle")),
            file,
            context(),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(nested, executor.request.cwd());
        assertEquals(binary.toAbsolutePath().normalize().toString(), executor.request.command().getFirst());
        assertTrue(executor.request.command().contains("--with-filename"));
        assertEquals(
            List.of("--", file.toAbsolutePath().normalize().toString()),
            executor.request.command().subList(
                executor.request.command().size() - 2,
                executor.request.command().size()
            )
        );
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(file));
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(nested));
    }

    @Test
    void directorySearchKeepsDirectoryAsWorkingDirectoryWithoutTargetOperand() throws Exception {
        vendorBinary();
        Path nested = Files.createDirectories(tempDir.resolve("src/pkg"));
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "needle")),
            nested,
            context(),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(nested, executor.request.cwd());
        assertFalse(executor.request.command().contains(nested.toString()));
    }

    @Test
    void fileSymlinkMountsLexicalAndRealTargets() throws Exception {
        vendorBinary();
        Path realDirectory = Files.createDirectories(tempDir.resolve("real"));
        Path realFile = Files.writeString(realDirectory.resolve("sample.py"), "needle\n");
        Path linkDirectory = Files.createDirectories(tempDir.resolve("links"));
        Path link = Files.createSymbolicLink(linkDirectory.resolve("sample-link.py"), realFile);
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "needle")),
            link,
            context(),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(linkDirectory, executor.request.cwd());
        assertEquals(link.toString(), executor.request.command().getLast());
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(link));
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(realFile.toRealPath()));
    }

    @Test
    void systemBinaryParentIsMountedReadOnly() throws Exception {
        Path systemDirectory = Files.createDirectories(tempDir.resolve("custom-bin"));
        Path executable = Files.writeString(systemDirectory.resolve("rg"), "#!/bin/sh\n");
        executable.toFile().setExecutable(true);
        RipgrepBinaryResolver resolver = RipgrepBinaryResolver.forTesting(
            new RipgrepPlatform("linux", "x86_64"),
            tempDir.resolve("missing-resources"),
            tempDir.resolve("cache"),
            RipgrepSearchRunner.class.getClassLoader(),
            List.of(systemDirectory)
        );
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        RipgrepSearchRunner runner = new RipgrepSearchRunner(
            executor,
            new RipgrepCommandBuilder(),
            resolver,
            Duration.ofSeconds(20)
        );

        RipgrepSearchResult result = runner.search(
            GrepQuery.fromInput(Map.of("pattern", "needle")),
            tempDir,
            new ToolUseContext(
                "ses_1",
                "msg_1",
                tempDir,
                Map.of(RipgrepBinaryResolver.MODE_KEY, "system")
            ),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertEquals(executable.toRealPath().toString(), executor.request.command().getFirst());
        assertTrue(executor.request.sandboxPolicy().allowRead().contains(systemDirectory.toRealPath()));
    }

    @Test
    void exitOneIsNoMatchSuccess() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(1, "", "", false, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "missing")),
            tempDir,
            context(),
            message -> {
            }
        );

        assertFalse(result.isError());
        assertTrue(result.lines().isEmpty());
    }

    @Test
    void exitTwoReturnsStderrAsError() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(2, "", "bad regex", false, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "[")),
            tempDir,
            context(),
            message -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.message().contains("bad regex"));
    }

    @Test
    void timeoutReturnsSpecificError() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(143, "", "", true, Optional.empty()));

        RipgrepSearchResult result = runner(executor).search(
            GrepQuery.fromInput(Map.of("pattern", "needle")),
            tempDir,
            context(),
            message -> {
            }
        );

        assertTrue(result.isError());
        assertTrue(result.message().contains("搜索超时"));
    }

    @Test
    void missingVendorBinaryReturnsResolverError() throws Exception {
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty()));
        RipgrepSearchResult result;
        try (URLClassLoader classLoader = new URLClassLoader(new URL[0], null)) {
            result = runner(executor, classLoader).search(
                GrepQuery.fromInput(Map.of("pattern", "needle")),
                tempDir,
                context(),
                message -> {
                }
            );
        }

        assertTrue(result.isError());
        assertTrue(result.message().contains("未找到随包 ripgrep"));
    }

    private RipgrepSearchRunner runner(Executor executor) {
        return new RipgrepSearchRunner(
            executor,
            new RipgrepCommandBuilder(),
            RipgrepBinaryResolver.forTesting(new RipgrepPlatform("linux", "x86_64"), tempDir),
            Duration.ofSeconds(20)
        );
    }

    private RipgrepSearchRunner runner(Executor executor, ClassLoader classLoader) {
        return new RipgrepSearchRunner(
            executor,
            new RipgrepCommandBuilder(),
            RipgrepBinaryResolver.forTesting(
                new RipgrepPlatform("linux", "x86_64"),
                tempDir,
                tempDir.resolve("cache"),
                classLoader
            ),
            Duration.ofSeconds(20)
        );
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("abortSignal", (AbortSignal) () -> false));
    }

    private Path vendorBinary() throws Exception {
        Path binary = tempDir.resolve("ripgrep/x86_64-linux/rg");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, "#!/bin/sh\n");
        binary.toFile().setExecutable(true);
        return binary;
    }

    private static final class RecordingExecutor implements Executor {
        private final ExecutionResult result;
        private ExecutionRequest request;

        private RecordingExecutor(ExecutionResult result) {
            this.result = result;
        }

        @Override
        public String name() {
            return "recording";
        }

        @Override
        public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, AbortSignal signal) {
            this.request = request;
            return result;
        }
    }
}
