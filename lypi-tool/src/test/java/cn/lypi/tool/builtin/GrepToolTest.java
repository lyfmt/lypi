package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.Executor;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepToolTest {
    @TempDir
    Path tempDir;

    @Test
    void defaultsToFilesWithMatchesOutput() throws Exception {
        Path binary = vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
            0,
            tempDir.resolve("a.txt") + "\n" + tempDir.resolve("b.txt") + "\n",
            "",
            false,
            Optional.empty()
        ));
        GrepTool tool = tool(executor);
        List<ToolProgress> progresses = new ArrayList<>();

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle"), context(), progresses::add);

        assertFalse(result.isError());
        assertEquals("Found 2 files\na.txt\nb.txt", result.output());
        assertEquals(binary.toString(), executor.request.command().getFirst());
        assertTrue(executor.request.command().contains("-l"));
        assertTrue(progresses.stream().anyMatch(progress ->
            progress.kind() == ToolProgressKind.PHASE && "scanning".equals(progress.phase())));
    }

    @Test
    void contentModeReturnsMatchingLines() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
            0,
            tempDir.resolve("a.txt") + ":2:needle line\n",
            "",
            false,
            Optional.empty()
        ));
        GrepTool tool = tool(executor);

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "output_mode", "content"), context(), message -> {
        });

        assertFalse(result.isError());
        assertEquals("a.txt:2:needle line", result.output());
    }

    @Test
    void countModeReturnsCountsAndSummary() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
            0,
            tempDir.resolve("a.txt") + ":2\n",
            "",
            false,
            Optional.empty()
        ));
        GrepTool tool = tool(executor);

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "output_mode", "count"), context(), message -> {
        });

        assertFalse(result.isError());
        assertTrue(result.output().contains("a.txt:2"));
        assertTrue(result.output().contains("Found 2 total occurrences across 1 file."));
    }

    @Test
    void rejectsSearchPathOutsideCwd() throws Exception {
        vendorBinary();
        GrepTool tool = tool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "path", "../"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("越过当前工作目录"));
    }

    @Test
    void rejectsMissingSearchPath() throws Exception {
        vendorBinary();
        GrepTool tool = tool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "path", "missing"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("搜索路径不存在"));
    }

    @Test
    void doesNotSearchSymlinkDirectoryOutsideWorkspace(@TempDir Path outsideDir) throws Exception {
        vendorBinary();
        Path outside = outsideDir.resolve("secret");
        Files.createDirectories(outside);
        Files.createSymbolicLink(tempDir.resolve("secret-link"), outside);
        GrepTool tool = tool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "path", "secret-link"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("符号链接越过当前工作目录"));
    }

    @Test
    void maxResultsBackfillsHeadLimitForCompatibility() throws Exception {
        vendorBinary();
        RecordingExecutor executor = new RecordingExecutor(new ExecutionResult(
            0,
            tempDir.resolve("a.txt") + "\n" + tempDir.resolve("b.txt") + "\n",
            "",
            false,
            Optional.empty()
        ));
        GrepTool tool = tool(executor);

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle", "maxResults", 1), context(), message -> {
        });

        assertFalse(result.isError());
        assertEquals("Found 1 file limit: 1\na.txt", result.output());
    }

    @Test
    void missingVendorBinaryReturnsError() {
        GrepTool tool = tool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        ToolResult<String> result = tool.execute(Map.of("pattern", "needle"), context(), message -> {
        });

        assertTrue(result.isError());
        assertTrue(result.output().contains("未找到随包 ripgrep"));
    }

    @Test
    void isReadOnlyAndConcurrencySafe() {
        GrepTool tool = tool(new RecordingExecutor(new ExecutionResult(0, "", "", false, Optional.empty())));

        assertTrue(tool.isReadOnly(Map.of()));
        assertTrue(tool.isConcurrencySafe(Map.of()));
        assertFalse(tool.isDestructive(Map.of()));
    }

    private GrepTool tool(Executor executor) {
        return new GrepTool(
            executor,
            new RipgrepSearchRunner(
                executor,
                new RipgrepCommandBuilder(),
                RipgrepBinaryResolver.forTesting(new RipgrepPlatform("linux", "x86_64"), tempDir),
                java.time.Duration.ofSeconds(20)
            ),
            new GrepResultFormatter()
        );
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of("toolUseId", "toolu_1"));
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
