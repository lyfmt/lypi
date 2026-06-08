package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiInputLoopTest {
    @Test
    void enterSubmitsDraftAndRerendersClearedInput() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(20, 4)
        );

        loop.acceptText("hello");
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("hello"), submit.submitted);
        assertEquals("", loop.draft());
        assertEquals("> ", frames.getLast().lines().skip(3).findFirst().orElseThrow());
    }

    @Test
    void modifiedEnterInsertsNewlineInsteadOfSubmitting() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.acceptText("hello");
        loop.acceptKey(TerminalKey.MODIFIED_ENTER);
        loop.acceptText("world");

        assertEquals("hello\nworld", loop.draft());
        assertEquals(List.of(), submit.submitted);
    }

    @Test
    void ctrlCClearsDraftBeforeInterruptingActiveTool() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.acceptText("draft");
        loop.acceptKey(TerminalKey.CTRL_C);
        loop.setToolRunning(true);
        loop.acceptKey(TerminalKey.CTRL_C);

        assertEquals("", loop.draft());
        assertEquals(1, submit.interrupts);
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private final List<String> submitted = new ArrayList<>();
        private int interrupts;

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
        }
    }
}
