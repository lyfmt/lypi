package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToolDisplayRendererRegistryTest {
    private static final List<String> TOOL_NAMES = List.of(
        "bash",
        "read",
        "write",
        "edit",
        "grep",
        "glob",
        "unknown_tool",
        "mcp__filesystem__read_file"
    );
    private static final List<String> DETAIL_RENDERING_TOOLS = List.of(
        "bash",
        "write",
        "edit",
        "unknown_tool",
        "mcp__filesystem__read_file"
    );

    private final ToolDisplayRendererRegistry registry = ToolDisplayRendererRegistry.defaults();

    @Test
    void everyCollapsedRendererFitsFiveLineBudgetIncludingOmissionMarker() {
        ToolDisplayBudget budget = ToolDisplayBudget.collapsed();

        for (String toolName : TOOL_NAMES) {
            ToolDisplayModel model = registry.render(block(toolName), false, budget);

            assertTrue(modelLineCount(model) <= 5, toolName + " exceeded collapsed budget");
            if (DETAIL_RENDERING_TOOLS.contains(toolName)) {
                assertTrue(hasOmissionMarker(model), toolName + " omitted details without a marker");
            }
        }
    }

    @Test
    void everyExpandedRendererFitsFortyLineBudgetAndPreservesHeadOrTailSemantics() {
        ToolDisplayBudget budget = ToolDisplayBudget.expanded(100);

        for (String toolName : TOOL_NAMES) {
            ToolDisplayModel model = registry.render(block(toolName), true, budget);

            assertTrue(modelLineCount(model) <= 40, toolName + " exceeded expanded budget");
            if (!DETAIL_RENDERING_TOOLS.contains(toolName)) {
                continue;
            }
            assertTrue(hasOmissionMarker(model), toolName + " omitted details without a marker");
            if ("bash".equals(toolName)) {
                assertTrue(model.previewLines().getFirst().contains("earlier lines"));
                assertEquals("line 100", model.previewLines().getLast());
            } else {
                assertEquals("line 1", model.previewLines().getFirst());
                assertTrue(model.previewLines().getLast().contains("more lines"));
            }
        }
    }

    private TuiToolBlock block(String toolName) {
        String details = String.join("\n", IntStream.rangeClosed(1, 100)
            .mapToObj(index -> "line " + index)
            .toList());
        return new TuiToolBlock(
            "tool:" + toolName,
            "msg_1",
            "toolu_" + toolName,
            toolName,
            TuiToolState.DONE,
            "call " + toolName,
            details,
            false
        );
    }

    private int modelLineCount(ToolDisplayModel model) {
        return 1 + model.summaryLines().size() + model.previewLines().size();
    }

    private boolean hasOmissionMarker(ToolDisplayModel model) {
        return model.previewLines().stream()
            .anyMatch(line -> line.contains("more lines") || line.contains("earlier lines"));
    }
}
