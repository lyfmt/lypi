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

    @Test
    void upAndDownNavigateSubmittedHistory() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.acceptText("first");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptText("second");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptText("draft");

        loop.acceptKey(TerminalKey.UP);
        assertEquals("second", loop.draft());
        loop.acceptKey(TerminalKey.UP);
        assertEquals("first", loop.draft());
        loop.acceptKey(TerminalKey.DOWN);
        assertEquals("second", loop.draft());
        loop.acceptKey(TerminalKey.DOWN);
        assertEquals("draft", loop.draft());
    }

    @Test
    void editingKeysMoveCursorDeleteLineUndoAndYank() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(30, 4));

        loop.acceptText("alpha beta gamma");
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptText("!");
        assertEquals("alpha beta gam!ma", loop.draft());

        loop.acceptKey(TerminalKey.CTRL_U);
        assertEquals("ma", loop.draft());
        loop.acceptKey(TerminalKey.CTRL_Y);
        assertEquals("alpha beta gam!ma", loop.draft());
        loop.acceptKey(TerminalKey.CTRL_Z);
        assertEquals("ma", loop.draft());
    }

    @Test
    void altYRotatesKillRingAfterYank() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(30, 4));

        loop.acceptText("alpha beta gamma");
        loop.acceptKey(TerminalKey.ALT_BACKSPACE);
        loop.acceptKey(TerminalKey.ALT_BACKSPACE);
        loop.acceptKey(TerminalKey.CTRL_Y);
        assertEquals("alpha beta", loop.draft());

        loop.acceptKey(TerminalKey.ALT_Y);

        assertEquals("alpha gamma", loop.draft());
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
