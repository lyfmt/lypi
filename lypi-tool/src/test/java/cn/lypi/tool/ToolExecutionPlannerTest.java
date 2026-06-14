package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolExecutionPlannerTest {
    @Test
    void groupsAdjacentReadOnlyConcurrencySafeRequests() {
        ToolExecutionPlanner planner = new ToolExecutionPlanner();
        Tool<Map<String, Object>, String> read = TestTools.echo("read", List.of(), true, true, false);

        List<ToolExecutionPlanner.Batch> batches = planner.plan(List.of(
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_1", "read"), read),
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_2", "read"), read)
        ));

        assertEquals(1, batches.size());
        assertTrue(batches.getFirst().parallel());
        assertEquals(2, batches.getFirst().calls().size());
    }

    @Test
    void serialRequestsKeepOriginalOrderAndSplitParallelBatches() {
        ToolExecutionPlanner planner = new ToolExecutionPlanner();
        Tool<Map<String, Object>, String> read = TestTools.echo("read", List.of(), true, true, false);
        Tool<Map<String, Object>, String> write = TestTools.echo("write", List.of(), false, false, true);

        List<ToolExecutionPlanner.Batch> batches = planner.plan(List.of(
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_1", "read"), read),
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_2", "write"), write),
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_3", "read"), read)
        ));

        assertEquals(List.of(true, false, true), batches.stream().map(ToolExecutionPlanner.Batch::parallel).toList());
        assertEquals("toolu_2", batches.get(1).calls().getFirst().request().toolUseId());
    }

    @Test
    void treatsFeatureDetectionErrorsAsSerial() {
        ToolExecutionPlanner planner = new ToolExecutionPlanner();
        Tool<Map<String, Object>, String> unstable = TestTools.throwingFeatures("unstable");

        List<ToolExecutionPlanner.Batch> batches = planner.plan(List.of(
            new ToolExecutionPlanner.ResolvedToolCall(request("toolu_1", "unstable"), unstable)
        ));

        assertEquals(1, batches.size());
        assertFalse(batches.getFirst().parallel());
    }

    private ToolUseRequest request(String toolUseId, String toolName) {
        return new ToolUseRequest(toolUseId, toolName, Map.of(), "msg_1");
    }
}
