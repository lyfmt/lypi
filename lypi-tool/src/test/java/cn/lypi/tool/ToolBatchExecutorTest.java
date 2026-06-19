package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolBatchExecutorTest {
    @Test
    void executesParallelBatchConcurrentlyButKeepsResultOrder() {
        ToolBatchExecutor executor = new ToolBatchExecutor(4);
        var firstTool = new TestTools.BlockingEchoTool("first", Duration.ofMillis(120), true, true);
        var secondTool = new TestTools.BlockingEchoTool("second", Duration.ZERO, true, true);
        ToolExecutionPlanner.Batch batch = new ToolExecutionPlanner.Batch(true, List.of(
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_1", "first", "one"), firstTool),
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_2", "second", "two"), secondTool)
        ));

        long started = System.nanoTime();
        List<ToolResult<?>> results = executor.execute(batch, (index, call) -> firstTextResult(call.request()));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertTrue(elapsedMillis < 240);
        assertEquals("one", results.get(0).newMessages().getFirst().content().getFirst().text());
        assertEquals("two", results.get(1).newMessages().getFirst().content().getFirst().text());
    }

    @Test
    void limitsParallelExecutionByMaxConcurrency() {
        ToolBatchExecutor executor = new ToolBatchExecutor(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        ToolExecutionPlanner.Batch batch = new ToolExecutionPlanner.Batch(true, List.of(
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_1", "first", "one"), TestTools.echo("first", List.of(), true, true, false)),
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_2", "second", "two"), TestTools.echo("second", List.of(), true, true, false))
        ));

        executor.execute(batch, (index, call) -> {
            int current = active.incrementAndGet();
            maxActive.updateAndGet(previous -> Math.max(previous, current));
            try {
                Thread.sleep(60L);
                return firstTextResult(call.request());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return TestTools.result(call.request().toolUseId(), "interrupted", true);
            } finally {
                active.decrementAndGet();
            }
        });

        assertEquals(1, maxActive.get());
    }

    private ToolUseRequest request(String toolUseId, String toolName, String text) {
        return new ToolUseRequest(toolUseId, toolName, Map.of("text", text), "msg_1");
    }

    private ToolResult<String> firstTextResult(ToolUseRequest request) {
        return TestTools.result(request.toolUseId(), request.input().get("text").toString(), false);
    }
}
