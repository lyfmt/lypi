package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class JLineTuiTransportConcurrencyTest {
    @Test
    void eventInputAndResizeRenderPathsShareUiMonitor() {
        StringBuilder order = new StringBuilder();
        JLineTuiTransport transport = new JLineTuiTransport(() -> order.append("render;"));

        transport.renderUnderUiLock();
        transport.runInputMutationForTest(() -> order.append("input;"));
        transport.runResizeMutationForTest(() -> order.append("resize;"));

        assertEquals("render;input;resize;", order.toString());
        assertEquals(3, transport.uiLockEntryCountForTest());
    }
}
