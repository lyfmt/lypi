package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResult;
import org.junit.jupiter.api.Test;

class ToolResultFactoryTest {
    @Test
    void createsErrorResultWithOptionalStatusMetadata() {
        ToolResult<String> result = new ToolResultFactory().error(
            "toolu_1",
            "denied",
            ToolExecutionStatus.CANCELLED
        );

        assertTrue(result.isError());
        assertEquals("denied", result.output());
        ToolResultContentBlock block = (ToolResultContentBlock) result.newMessages().getFirst().content().getFirst();
        assertEquals("toolu_1", block.toolUseId());
        assertEquals("denied", block.text());
        assertEquals("CANCELLED", block.metadata().get("status"));
    }
}
