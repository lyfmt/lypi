package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.AbortSignal;
import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolAbortSupportTest {
    @Test
    void extractsAbortSignalFromMetadata() {
        AbortSignal signal = () -> true;
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of("abortSignal", signal));

        assertSame(signal, ToolAbortSupport.signal(context));
        assertTrue(ToolAbortSupport.aborted(context));
    }

    @Test
    void returnsNonAbortedFallbackWhenMissing() {
        ToolUseContext context = new ToolUseContext("ses_1", "msg_1", Path.of("."), Map.of());

        assertFalse(ToolAbortSupport.signal(context).aborted());
        assertFalse(ToolAbortSupport.aborted(context));
    }
}
