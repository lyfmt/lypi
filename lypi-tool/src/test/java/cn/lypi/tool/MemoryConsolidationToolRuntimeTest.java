package cn.lypi.tool;

import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.runtime.ToolRuntimeInvocation;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryConsolidationToolRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotPreservesDelegateToolsForPromptCachePrefix() {
        RecordingRuntime delegate = runtime(tempDir);
        MemoryConsolidationToolRuntime runtime = new MemoryConsolidationToolRuntime(
            delegate,
            new MemoryConsolidationWritePolicy(tempDir, tempDir.resolve("home/.ly-pi"))
        );

        assertEquals(List.of("read", "grep", "glob", "edit", "write", "bash"),
            runtime.snapshot().tools().stream().map(tool -> tool.name()).toList());
        assertTrue(runtime.resolve("read").isPresent());
        assertTrue(runtime.resolve("bash").isPresent());
    }

    @Test
    void deniesToolOutsidePolicyWithoutCallingDelegate() {
        RecordingRuntime delegate = runtime(tempDir);
        MemoryConsolidationToolRuntime runtime = new MemoryConsolidationToolRuntime(
            delegate,
            new MemoryConsolidationWritePolicy(tempDir, tempDir.resolve("home/.ly-pi"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("command", "date"), "msg_1")),
            TestTools.context(PermissionMode.ASK)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("denied tool"));
        assertEquals(0, delegate.requests.size());
    }

    @Test
    void deniesWriteOutsideMemoryTargetsWithoutCallingDelegate() {
        RecordingRuntime delegate = runtime(tempDir);
        MemoryConsolidationToolRuntime runtime = new MemoryConsolidationToolRuntime(
            delegate,
            new MemoryConsolidationWritePolicy(tempDir, tempDir.resolve("home/.ly-pi"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("path", "src/Main.java"), "msg_1")),
            TestTools.context(PermissionMode.ASK)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("denied write path"));
        assertEquals(0, delegate.requests.size());
    }

    @Test
    void delegatesAllowedWriteTargets() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve(".ly-pi/memory/project"));
        RecordingRuntime delegate = runtime(tempDir);
        MemoryConsolidationToolRuntime runtime = new MemoryConsolidationToolRuntime(
            delegate,
            new MemoryConsolidationWritePolicy(tempDir, tempDir.resolve("home/.ly-pi"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("path", ".ly-pi/memory/project/facts.md"), "msg_1")),
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1", "entry_1")
        ).getFirst();

        assertFalse(result.isError());
        assertEquals(1, delegate.requests.size());
        assertEquals("write", delegate.requests.getFirst().toolName());
    }

    @Test
    void preservesCanonicalPermissionRuntimeStateBeforeDelegating() throws Exception {
        java.nio.file.Files.createDirectories(tempDir.resolve(".ly-pi/memory/project"));
        RecordingRuntime delegate = runtime(tempDir);
        MemoryConsolidationToolRuntime runtime = new MemoryConsolidationToolRuntime(
            delegate,
            new MemoryConsolidationWritePolicy(tempDir, tempDir.resolve("home/.ly-pi"))
        );
        PermissionRuntimeState runtimeState = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);

        runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "write", Map.of("path", ".ly-pi/memory/project/facts.md"), "msg_1")),
            context(runtimeState)
        );

        assertEquals(runtimeState, delegate.contexts.getFirst().permissionRuntimeState());
    }

    private static ContextSnapshot context(PermissionRuntimeState runtimeState) {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            runtimeState,
            null
        );
    }

    private static RecordingRuntime runtime(Path cwd) {
        RecordingRuntime runtime = new RecordingRuntime(cwd);
        runtime.tools.add(TestTools.echo("read", List.of("cat"), true, true, false));
        runtime.tools.add(TestTools.echo("grep", List.of(), true, true, false));
        runtime.tools.add(TestTools.echo("glob", List.of(), true, true, false));
        runtime.tools.add(TestTools.echo("edit", List.of(), false, false, true));
        runtime.tools.add(TestTools.echo("write", List.of(), false, false, true));
        runtime.tools.add(TestTools.echo("bash", List.of(), false, false, true));
        return runtime;
    }

    private static final class RecordingRuntime implements ToolRuntimePort {
        private final Path cwd;
        private final List<Tool<?, ?>> tools = new ArrayList<>();
        private final List<ToolUseRequest> requests = new ArrayList<>();
        private final List<ContextSnapshot> contexts = new ArrayList<>();

        private RecordingRuntime(Path cwd) {
            this.cwd = cwd;
        }

        @Override
        public void register(Tool<?, ?> tool) {
            tools.add(tool);
        }

        @Override
        public Optional<Tool<?, ?>> resolve(String nameOrAlias) {
            return tools.stream()
                .filter(tool -> tool.name().equals(nameOrAlias) || tool.aliases().contains(nameOrAlias))
                .findFirst();
        }

        @Override
        public ToolRegistrySnapshot snapshot() {
            return new ToolRegistrySnapshot(tools.stream()
                .map(tool -> new cn.lypi.contracts.tool.ToolDescriptor(
                    tool.name(),
                    tool.aliases(),
                    true,
                    false
                ))
                .toList());
        }

        @Override
        public Path cwd() {
            return cwd;
        }

        @Override
        public List<ToolResult<?>> execute(List<ToolUseRequest> requests, ContextSnapshot context) {
            return execute(requests, context, null);
        }

        @Override
        public List<ToolResult<?>> execute(
            List<ToolUseRequest> requests,
            ContextSnapshot context,
            ToolRuntimeInvocation invocation
        ) {
            this.requests.addAll(requests);
            this.contexts.add(context);
            List<ToolResult<?>> results = new ArrayList<>();
            for (ToolUseRequest request : requests) {
                results.add(TestTools.result(request.toolUseId(), request.toolName(), false));
            }
            return List.copyOf(results);
        }
    }
}
