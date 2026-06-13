package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FilteredToolRuntimeTest {
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
        assertTrue(runtime.resolve("cat").isPresent());
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
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertTrue(result.isError());
        assertTrue(result.output().toString().contains("not allowed"));
    }

    @Test
    void delegatesExecutionForAllowedAliasUsingOriginalRequestName() {
        DefaultToolRuntime delegate = runtimeWithReadGrepGlobAndBash();
        FilteredToolRuntime runtime = new FilteredToolRuntime(
            delegate,
            new SubagentToolPolicy(List.of("cat"), List.of("read", "grep", "glob"))
        );

        ToolResult<?> result = runtime.execute(
            List.of(new ToolUseRequest("toolu_1", "cat", Map.of("text", "hello"), "msg_1")),
            TestTools.context(PermissionMode.DEFAULT_EXECUTE)
        ).getFirst();

        assertFalse(result.isError());
        assertEquals("hello", result.output());
    }

    @Test
    void reportsConfiguredCwdFromDelegate() {
        DefaultToolRuntime delegate = new DefaultToolRuntime(
            ToolRuntimeOptions.builder().cwd(Path.of("/tmp/project")).build(),
            (request, context) -> TestTools.decision(cn.lypi.contracts.security.PermissionBehavior.ALLOW, "allowed")
        );
        FilteredToolRuntime runtime = new FilteredToolRuntime(delegate, SubagentToolPolicy.empty());

        assertEquals(Path.of("/tmp/project"), runtime.cwd());
    }

    private static DefaultToolRuntime runtimeWithReadGrepGlobAndBash() {
        DefaultToolRuntime runtime = new DefaultToolRuntime(
            (request, context) -> TestTools.decision(cn.lypi.contracts.security.PermissionBehavior.ALLOW, "allowed")
        );
        runtime.register(TestTools.echo("read", List.of("cat"), true, true, false));
        runtime.register(TestTools.echo("grep", List.of(), true, true, false));
        runtime.register(TestTools.echo("glob", List.of(), true, true, false));
        runtime.register(TestTools.echo("bash", List.of("sh"), false, false, true));
        runtime.register(TestTools.echo("write", List.of(), false, false, true));
        return runtime;
    }
}
