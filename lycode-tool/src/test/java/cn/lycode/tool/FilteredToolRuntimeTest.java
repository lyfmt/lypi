package cn.lycode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.subagent.SubagentToolPolicy;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilteredToolRuntimeTest {
    @TempDir
    Path tempDir;

    @Test
    void snapshotOnlyContainsEffectiveTools() {
        DefaultToolRuntime delegate = runtimeWithReadGrepGlobAndBash();
        FilteredToolRuntime runtime = new FilteredToolRuntime(
            delegate,
            new SubagentToolPolicy(List.of("bash"), List.of("read", "grep", "glob", "bash"))
        );

        assertEquals(
            List.of("read", "grep", "glob", "bash"),
            runtime.snapshot().tools().stream().map(tool -> tool.name()).toList()
        );
        assertTrue(runtime.resolve("read").isPresent());
        assertFalse(runtime.resolve("cat").isPresent());
        assertTrue(runtime.resolve("bash").isPresent());
        assertFalse(runtime.resolve("write").isPresent());
    }

    @Test
    void deniesExecutionForToolOutsideEffectivePolicy() {
        DefaultToolRuntime delegate = runtimeWithReadGrepGlobAndBash();
        FilteredToolRuntime runtime = new FilteredToolRuntime(
            delegate,
            new SubagentToolPolicy(List.of(), List.of("read", "grep", "glob"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "bash", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.ASK)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("not allowed"));
    }

    @Test
    void rejectsAliasExecutionEvenWhenCanonicalToolIsAllowed() {
        DefaultToolRuntime delegate = runtimeWithReadGrepGlobAndBash();
        FilteredToolRuntime runtime = new FilteredToolRuntime(
            delegate,
            new SubagentToolPolicy(List.of("cat"), List.of("read", "grep", "glob"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "cat", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.ASK)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("canonical"));
    }

    @Test
    void rejectsUnknownEffectiveToolAtConstruction() {
        DefaultToolRuntime delegate = runtimeWithReadGrepGlobAndBash();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            new FilteredToolRuntime(
                delegate,
                new SubagentToolPolicy(List.of("missing"), List.of("read", "grep", "glob", "missing"))
            )
        );

        assertTrue(exception.getMessage().contains("missing"));
    }

    @Test
    void reportsConfiguredCwdFromDelegate() {
        DefaultToolRuntime delegate = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().cwd(Path.of("/tmp/project")).build(),
            (request, context) -> TestTools.decision(cn.lycode.contracts.security.PermissionBehavior.ALLOW, "allowed")
        );
        FilteredToolRuntime runtime = new FilteredToolRuntime(delegate, SubagentToolPolicy.empty());

        assertEquals(Path.of("/tmp/project"), runtime.cwd());
    }

    @Test
    void propagatesValidCwdAndIgnoresInvalidDeltasBetweenDelegatedCalls() throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path nested = Files.createDirectories(workspace.resolve("nested"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path symlinkEscape = workspace.resolve("escape");
        Files.createSymbolicLink(symlinkEscape, outside);
        AtomicReference<ToolUseContext> captured = new AtomicReference<>();
        DefaultToolRuntime delegate = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().cwd(workspace).build(),
            (request, context) -> TestTools.decision(
                cn.lycode.contracts.security.PermissionBehavior.ALLOW,
                "allowed"
            )
        );
        delegate.register(TestTools.stateDeltaEcho("cd_valid", nested));
        delegate.register(TestTools.stateDeltaEcho("cd_missing", workspace.resolve("missing")));
        delegate.register(TestTools.stateDeltaEcho("cd_outside", outside));
        delegate.register(TestTools.stateDeltaEcho("cd_symlink", symlinkEscape));
        delegate.register(TestTools.contextCapturingEcho("probe", captured));
        FilteredToolRuntime runtime = new FilteredToolRuntime(
            delegate,
            new SubagentToolPolicy(
                List.of(),
                List.of("cd_valid", "cd_missing", "cd_outside", "cd_symlink", "probe")
            )
        );

        runtime.execute(
            List.of(
                new ToolUseRequest("toolu_valid", "cd_valid", Map.of(), "msg_1"),
                new ToolUseRequest("toolu_missing", "cd_missing", Map.of(), "msg_1"),
                new ToolUseRequest("toolu_outside", "cd_outside", Map.of(), "msg_1"),
                new ToolUseRequest("toolu_symlink", "cd_symlink", Map.of(), "msg_1"),
                new ToolUseRequest("toolu_probe", "probe", Map.of(), "msg_1")
            ),
            TestTools.context(PermissionMode.ASK),
            new ToolRuntimeInvocation("ses_1", "turn_1").withCwd(workspace)
        );

        assertEquals(workspace, captured.get().workspaceRoot());
        assertEquals(nested, captured.get().cwd());
    }

    private static DefaultToolRuntime runtimeWithReadGrepGlobAndBash() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            (request, context) -> TestTools.decision(cn.lycode.contracts.security.PermissionBehavior.ALLOW, "allowed")
        );
        runtime.register(TestTools.echo("read", List.of("cat"), true, true, false));
        runtime.register(TestTools.echo("grep", List.of(), true, true, false));
        runtime.register(TestTools.echo("glob", List.of(), true, true, false));
        runtime.register(TestTools.echo("bash", List.of("sh"), false, false, true));
        runtime.register(TestTools.echo("write", List.of(), false, false, true));
        return runtime;
    }
}
