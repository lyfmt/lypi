package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
        assertEquals("\033[48;5;236m> |CURSOR|\033[0m", inputLine(frames.getLast()));
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

        assertEquals("\033[48;5;236m> alpha be|CURSOR|ta\033[0m", inputLine(frames.getLast()));
    }

    @Test
    void backspaceDeletesPreviousCharacterAndRerendersInput() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(30, 4)
        );

        loop.acceptText("abcd");
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.BACKSPACE);

        assertEquals("acd", loop.draft());
        assertEquals("\033[48;5;236m> a|CURSOR|cd\033[0m", inputLine(frames.getLast()));
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
    void upAndDownSelectPermissionOptionAndEnterSubmitsSelectedOption() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(4),
            new TuiLayout(40, 6),
            () -> permissionViewWithOptions("allow_once", "escape_cancel")
        );

        loop.acceptKey(TerminalKey.DOWN);
        loop.acceptKey(TerminalKey.DOWN);
        loop.acceptKey(TerminalKey.UP);
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("perm_toolu_1:toolu_1:remember"), submit.permissionOptions);
        assertTrue(frames.getFirst().contains("> 允许并记住"));
        assertTrue(frames.get(2).contains("> 允许并记住"));
    }

    @Test
    void permissionPromptTakesPriorityOverSlashOverlayNavigation() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(5),
            new TuiLayout(40, 7),
            () -> permissionViewWithOptions("allow_once", "escape_cancel"),
            () -> new SlashCommandPicker(List.of("/model", "/mode"))
        );

        loop.acceptText("/");
        loop.acceptKey(TerminalKey.DOWN);
        loop.acceptKey(TerminalKey.TAB);
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals("/", loop.draft());
        assertEquals(List.of("perm_toolu_1:toolu_1:remember"), submit.permissionOptions);
        assertTrue(frames.getLast().contains("permission toolu_1: Need approval"));
        assertTrue(!frames.getLast().contains("/model"));
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
            () -> permissionViewWithOptions("allow_once", "escape_cancel")
        );

        loop.acceptKey(TerminalKey.ESC);
        loop.acceptKey(TerminalKey.CTRL_C);

        assertEquals(
            List.of("perm_toolu_1:toolu_1:escape_cancel", "perm_toolu_1:toolu_1:escape_cancel"),
            submit.permissionOptions
        );
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
    void slashOverlayShowsCandidatesAndAcceptsSelection() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(6),
            new TuiLayout(40, 8),
            null,
            () -> SlashCommandPicker.withTemplates(List.of("review"))
        );

        loop.acceptText("/");

        assertTrue(frames.getLast().contains("> /model"));
        assertTrue(frames.getLast().contains("  /compact"));

        loop.acceptText("th");
        assertTrue(frames.getLast().contains("> /thinking"));

        loop.acceptKey(TerminalKey.ENTER);

        assertEquals("/thinking ", loop.draft());
        assertEquals(List.of(), submit.submitted);
    }

    @Test
    void slashOverlayUsesArrowKeysAndEscWithoutHistoryNavigation() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(3),
            new TuiLayout(40, 5),
            null,
            () -> new SlashCommandPicker(List.of("/model", "/mode", "/compact"))
        );

        loop.acceptText("first");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptText("/");
        loop.acceptKey(TerminalKey.DOWN);
        loop.acceptKey(TerminalKey.TAB);

        assertEquals("/mode ", loop.draft());

        loop.acceptKey(TerminalKey.CTRL_U);
        loop.acceptText("/");
        loop.acceptKey(TerminalKey.ESC);
        loop.acceptKey(TerminalKey.UP);

        assertEquals("first", loop.draft());
    }

    @Test
    void slashOverlayScrollsSelectedCandidateIntoVisibleWindow() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(6),
            new TuiLayout(40, 8),
            null,
            () -> new SlashCommandPicker(List.of(
                "/model",
                "/thinking",
                "/mode",
                "/permission-mode",
                "/compact",
                "/review"
            ))
        );

        loop.acceptText("/");
        for (int i = 0; i < 5; i++) {
            loop.acceptKey(TerminalKey.DOWN);
        }

        assertTrue(frames.getLast().contains("> /review"));
        assertTrue(!frames.getLast().contains("> /model"));

        loop.acceptKey(TerminalKey.TAB);

        assertEquals("/review ", loop.draft());
    }

    @Test
    void unknownSlashWithNoOverlayCandidatesSubmitsAsNormalInput() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(3),
            new TuiLayout(40, 5),
            null,
            () -> new SlashCommandPicker(List.of("/model"))
        );

        loop.acceptText("/unknown");
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("/unknown"), submit.submitted);
        assertEquals("", loop.draft());
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

    private static String inputLine(String frame) {
        List<String> lines = frame.lines().toList();
        return lines.get(lines.size() - 2);
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

    private static TuiViewModel permissionViewWithOptions(String defaultOptionId, String cancelOptionId) {
        return new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.of(new PermissionPromptView(
                "perm_toolu_1",
                "toolu_1",
                "Need approval",
                "bash:npm test",
                defaultOptionId,
                cancelOptionId,
                List.of(
                    new PermissionOption("allow_once", PermissionOptionKind.ALLOW_ONCE, "允许一次", "", Optional.empty(), Map.of()),
                    new PermissionOption("remember", PermissionOptionKind.ALLOW_ONCE, "允许并记住", "", Optional.empty(), Map.of()),
                    new PermissionOption("escape_cancel", PermissionOptionKind.CANCEL, "取消", "", Optional.empty(), Map.of())
                ),
                defaultOptionId
            )),
            Optional.empty()
        );
    }
}
