package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
        assertEquals("> |CURSOR|", frames.getLast().lines().skip(3).findFirst().orElseThrow());
    }

    @Test
    void rendersCursorAtCurrentEditorPosition() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(30, 4)
        );

        loop.acceptText("alpha beta");
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.LEFT);

        assertEquals("> alpha be|CURSOR|ta", frames.getLast().lines().skip(3).findFirst().orElseThrow());
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
    void ctrlCRequestsExitWhenInputIsEmptyAndNoToolIsRunning() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.acceptKey(TerminalKey.CTRL_C);

        assertEquals(1, submit.exits);
        assertEquals(true, loop.exitRequested());
    }

    @Test
    void enterSubmitsPermissionDefaultOptionWhenPromptIsOpen() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(40, 4),
            () -> permissionView("allow_once", "cancel")
        );

        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("perm_toolu_1:toolu_1:allow_once"), submit.permissionOptions);
        assertEquals(List.of(), submit.submitted);
    }

    @Test
    void escapeAndCtrlCCancelPermissionPromptInsteadOfExitOrInterrupt() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(40, 4),
            () -> permissionView("allow_once", "cancel")
        );

        loop.acceptKey(TerminalKey.ESC);
        loop.acceptKey(TerminalKey.CTRL_C);

        assertEquals(List.of("perm_toolu_1:toolu_1:cancel", "perm_toolu_1:toolu_1:cancel"), submit.permissionOptions);
        assertEquals(0, submit.exits);
        assertEquals(0, submit.interrupts);
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
        private final List<String> permissionOptions = new ArrayList<>();
        private int interrupts;
        private int exits;

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
        }

        @Override
        public void requestExit(String reason) {
            exits++;
        }

        @Override
        public void submitPermissionOption(String requestId, String toolUseId, String optionId) {
            permissionOptions.add(requestId + ":" + toolUseId + ":" + optionId);
        }
    }

    private static TuiViewModel permissionView(String defaultOptionId, String cancelOptionId) {
        return new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.of(new PermissionPromptView("perm_toolu_1", "toolu_1", "Need approval", "bash:npm test", defaultOptionId, cancelOptionId)),
            Optional.empty()
        );
    }
}
