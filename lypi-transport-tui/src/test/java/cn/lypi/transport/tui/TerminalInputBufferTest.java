package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TerminalInputBufferTest {
    @Test
    void buffersSplitCsiSequenceUntilComplete() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(), buffer.accept("\033[13"));
        assertEquals(List.of(TerminalInputSegment.key("\033[13;5u")), buffer.accept(";5u"));
    }

    @Test
    void emitsStandaloneEscapeAsKeySequence() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(TerminalInputSegment.key("\033")), buffer.accept("\033"));
    }

    @Test
    void splitsTextPasteAndRemainingInputFromOneChunk() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(
            TerminalInputSegment.text("hi"),
            TerminalInputSegment.paste("alpha\nbeta"),
            TerminalInputSegment.key("\r")
        ), buffer.accept("hi\033[200~alpha\nbeta\033[201~\r"));
    }

    @Test
    void accumulatesPasteAcrossChunksAndProcessesRemainingText() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(), buffer.accept("\033[200~alpha\n"));
        assertEquals(List.of(
            TerminalInputSegment.paste("alpha\nbeta"),
            TerminalInputSegment.text("tail")
        ), buffer.accept("beta\033[201~tail"));
    }

    @Test
    void flushesIncompleteKeySequenceWhenNoMoreInputArrives() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(), buffer.accept("\033[<35"));
        assertEquals(List.of(TerminalInputSegment.key("\033[<35")), buffer.flushIncompleteKeySequence());
    }

    @Test
    void keepsPendingPasteWhenFlushingIncompleteKeySequence() {
        TerminalInputBuffer buffer = new TerminalInputBuffer();

        assertEquals(List.of(), buffer.accept("\033[200~alpha\n"));
        assertEquals(List.of(), buffer.flushIncompleteKeySequence());
        assertEquals(List.of(TerminalInputSegment.paste("alpha\nbeta")), buffer.accept("beta\033[201~"));
    }
}
