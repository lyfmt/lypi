package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import org.junit.jupiter.api.Test;

class ToolResultBudgeterTest {
    @Test
    void leavesSmallToolResultUnchanged() {
        ToolResult<String> result = TestTools.result("toolu_1", "short", false);

        ToolResult<String> budgeted = new ToolResultBudgeter().apply("toolu_1", "read", result, 20);

        assertSame(result, budgeted);
    }

    @Test
    void replacesOversizedToolResultTextWithPreview() {
        ToolResult<String> result = TestTools.result("toolu_1", "0123456789abcdef", false);

        ToolResult<String> budgeted = new ToolResultBudgeter().apply("toolu_1", "read", result, 8);

        ToolResultContentBlock block = (ToolResultContentBlock) budgeted.newMessages().getFirst().content().getFirst();
        assertTrue(block.text().startsWith("01234567"));
        assertTrue(block.text().contains("工具结果已超出预算"));
        assertTrue(budgeted.replacement().isPresent());
        assertEquals("toolu_1", budgeted.replacement().orElseThrow().toolUseId());
    }
}
