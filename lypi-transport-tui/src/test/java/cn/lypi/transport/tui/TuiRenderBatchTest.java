package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class TuiRenderBatchTest {
    @Test
    void twoArgumentConstructorDefaultsToIncrementalUpdate() {
        TuiRenderBatch batch = new TuiRenderBatch(
            List.of(new TerminalLine("history")),
            TuiRenderFrame.fromTextLines(List.of("> |CURSOR|"))
        );

        assertEquals(TuiRenderIntent.UPDATE, batch.intent());
    }

    @Test
    void explicitIntentMustNotBeNull() {
        TuiRenderFrame surface = TuiRenderFrame.fromTextLines(List.of("> |CURSOR|"));

        assertThrows(NullPointerException.class, () -> new TuiRenderBatch(List.of(), surface, null));
    }
}
