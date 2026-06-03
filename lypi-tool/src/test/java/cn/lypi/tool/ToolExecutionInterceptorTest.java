package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolExecutionInterceptorTest {
    @Test
    void beforeInterceptorCanBlockExecution() {
        ToolExecutionInterceptor interceptor = ToolExecutionInterceptor.before((request, tool, context) ->
            ToolExecutionInterceptor.BeforeResult.block("blocked by test")
        );

        ToolExecutionInterceptor.BeforeResult result = interceptor.beforeExecute(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.echo("read", List.of(), true, true, false),
            TestTools.toolContext(PermissionMode.DEFAULT_EXECUTE)
        );

        assertTrue(result.blocked());
        assertEquals("blocked by test", result.message());
    }

    @Test
    void combinedInterceptorAppliesAfterResultsInOrder() {
        ToolExecutionInterceptor first = ToolExecutionInterceptor.after((request, tool, context, result) ->
            TestTools.result(request.toolUseId(), "first", false)
        );
        ToolExecutionInterceptor second = ToolExecutionInterceptor.after((request, tool, context, result) ->
            TestTools.result(request.toolUseId(), result.output() + "-second", false)
        );

        ToolResult<?> result = ToolExecutionInterceptors.combine(List.of(first, second)).afterExecute(
            new ToolUseRequest("toolu_1", "read", Map.of(), "msg_1"),
            TestTools.echo("read", List.of(), true, true, false),
            TestTools.toolContext(PermissionMode.DEFAULT_EXECUTE),
            TestTools.result("toolu_1", "original", false)
        );

        assertEquals("first-second", result.output());
    }
}
