package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiRendererTest {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";

    @Test
    void rendersFullTranscriptStatusAndInputForScrollback() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "hello world", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "tool:running"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(12, 5), "draft");

        assertEquals(5, lines.size());
        assertEquals("hello world", lines.get(0));
        assertInputBorder(lines.get(1), 12);
        assertInputContent(lines.get(2), "> draft");
        assertInputBorder(lines.get(3), 12);
        assertTrue(lines.getLast().contains("tool"));
    }

    @Test
    void statusBarPreservesToolOnNarrowWidth() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("session-long", "very-long-model", "execute", "tool:running"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(10, 3), "");

        assertTrue(lines.getLast().contains("tool"));
    }

    @Test
    void statusBarDoesNotRenderInternalRuntimeFields() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState(
                "ses_1",
                "gpt-5.4",
                "EXECUTE",
                "DEFAULT_EXECUTE",
                "long-project-name",
                "leaf_1234567890",
                "1234/200000tok",
                true
            ),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(120, 3), "");

        assertEquals("ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE", lines.getLast());
        assertFalse(lines.getLast().contains("cwd:"));
        assertFalse(lines.getLast().contains("leaf:"));
        assertFalse(lines.getLast().contains("ctx:"));
        assertFalse(lines.getLast().contains("tool:interruptible"));
    }

    @Test
    void messageBlocksUseMarkdownRenderer() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "## Done ##\n- [x] task", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(20, 6), "");

        assertEquals("Done", lines.get(0));
        assertEquals("[x] task", lines.get(1));
    }

    @Test
    void inputLineMarksHardwareCursorAtEditorCursor() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(30, 3), "alpha beta", 6);

        assertInputBorder(lines.get(0), 30);
        assertInputContent(lines.get(lines.size() - 2), "> alpha |CURSOR|" + INPUT_CURSOR + "beta");
    }

    @Test
    void inputLineKeepsCursorMarkerAfterNarrowWrapping() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(8, 3), "abcdefgh", 8);

        assertEquals(3, lines.size());
        assertInputBorder(lines.get(0), 8);
        assertInputContent(lines.get(lines.size() - 2), "fgh|CURSOR|" + INPUT_CURSOR);
    }

    @Test
    void visibleCursorDoesNotPushInputContentPastLayoutWidth() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(8, 4), "abcdef", 6);

        for (String line : lines) {
            assertTrue(AnsiWidth.displayWidth(line.replace(TerminalFrameRenderer.CURSOR_MARKER, "")) <= 8);
        }
    }

    @Test
    void longInputSoftWrapsInsideAnchoredBottomInputBlock() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiMessageBlock("b1", "m1", "assistant", "line1", false),
                new TuiMessageBlock("b2", "m2", "assistant", "line2", false),
                new TuiMessageBlock("b3", "m3", "assistant", "line3", false),
                new TuiMessageBlock("b4", "m4", "assistant", "line4", false)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(8, 6), "abcdefghij", 10);

        assertEquals(6, lines.size());
        assertEquals("line4", lines.get(0));
        assertInputBorder(lines.get(1), 8);
        assertEquals("\033[48;5;236m> abcde\033[0m", lines.get(2));
        assertEquals("\033[48;5;236mfghij|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(3));
        assertInputBorder(lines.get(4), 8);
        assertTrue(lines.get(5).contains("ses_1"));
    }

    @Test
    void explicitNewlineStartsNewInputRowWithoutSplittingDraftSemantics() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(12, 5), "hello\nworld", 11);

        assertEquals(5, lines.size());
        assertInputBorder(lines.get(0), 12);
        assertEquals("\033[48;5;236m> hello\033[0m", lines.get(1));
        assertEquals("\033[48;5;236mworld|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(2));
        assertInputBorder(lines.get(3), 12);
        assertTrue(lines.get(4).contains("ses_1"));
    }

    @Test
    void inputViewportShowsLatestRowsWhenDraftExceedsTerminalHeight() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "history", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(10, 6), "one\ntwo\nthree\nfour", 18);

        assertEquals(6, lines.size());
        assertFalse(lines.contains("history"));
        assertInputBorder(lines.get(0), 10);
        assertEquals("\033[48;5;236mtwo\033[0m", lines.get(1));
        assertEquals("\033[48;5;236mthree\033[0m", lines.get(2));
        assertEquals("\033[48;5;236mfour|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(3));
        assertInputBorder(lines.get(4), 10);
    }

    @Test
    void inputBlockNeverExceedsTerminalHeightWhenDraftHasManyRows() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "history", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(10, 3), "one\ntwo\nthree\nfour", 18);

        assertEquals(3, lines.size());
        assertFalse(lines.contains("history"));
        assertInputBorder(lines.get(0), 10);
        assertEquals("\033[48;5;236mfour|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(1));
        assertTrue(lines.get(2).contains("ses_1"));
    }

    @Test
    void permissionPromptIsRenderedInTranscriptArea() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(5);
        PermissionUpdate rememberUpdate = new PermissionUpdate(
            PermissionRuleSource.SESSION,
            new PermissionRule(
                PermissionRuleSource.SESSION,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "npm test"),
                "remember npm test"
            )
        );
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.of(new PermissionPromptView(
                "perm_toolu_1",
                "toolu_1",
                "Need approval",
                "bash:npm test",
                "allow_once",
                "escape_cancel",
                List.of(
                    new PermissionOption("allow_once", PermissionOptionKind.ALLOW_ONCE, "允许一次", "", Optional.empty(), Map.of()),
                    new PermissionOption(
                        "remember",
                        PermissionOptionKind.ALLOW_AND_REMEMBER,
                        "允许并记住",
                        "",
                        Optional.of(rememberUpdate),
                        Map.of()
                    ),
                    new PermissionOption("escape_cancel", PermissionOptionKind.CANCEL, "取消", "", Optional.empty(), Map.of())
                ),
                "allow_once"
            )),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 9), "");

        assertEquals("permission toolu_1: Need approval", lines.get(0));
        assertEquals("rule: bash:npm test", lines.get(1));
        assertEquals("> 允许一次", lines.get(2));
        assertEquals("  允许并记住", lines.get(3));
    }

    @Test
    void diffViewIsRenderedInTranscriptArea() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(4);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.of(new DiffView(
                "1 file changed",
                List.of(new GitDiffFileView(Path.of("src/App.java"), GitDiffStatus.MODIFIED, "Modified", Map.of())),
                "+new line",
                false,
                Map.of()
            ))
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 8), "");

        assertEquals("diff: 1 file changed", lines.get(0));
        assertEquals("M src/App.java", lines.get(1));
        assertEquals("", lines.get(2));
        assertEquals("+new line", lines.get(3));
    }

    @Test
    void runtimeLineUsesTranscriptSpaceOnlyWhenActive() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(3);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiMessageBlock("b1", "m1", "assistant", "line1", false),
                new TuiMessageBlock("b2", "m2", "assistant", "line2", false),
                new TuiMessageBlock("b3", "m3", "assistant", "line3", false)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "running", "default"),
            "retrying attempt 2 rate limit",
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 8), "");

        assertEquals("line1", lines.get(0));
        assertEquals("line2", lines.get(1));
        assertEquals("line3", lines.get(2));
        assertEquals("· retrying attempt 2 rate limit", lines.get(3));
        assertInputBorder(lines.get(4), 40);
        assertInputContent(lines.get(5), "> ");
        assertInputBorder(lines.get(6), 40);
        assertTrue(lines.getLast().contains("ses_1"));

        TuiScreen screenWithoutRuntime = new TuiScreen(3);
        TuiViewModel withoutRuntime = new TuiViewModel(
            view.blocks(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
        List<String> withoutRuntimeLines = renderer.render(withoutRuntime, screenWithoutRuntime, new TuiLayout(40, 8), "");

        assertEquals("line1", withoutRuntimeLines.get(0));
        assertEquals("line2", withoutRuntimeLines.get(1));
        assertEquals("line3", withoutRuntimeLines.get(2));
    }

    @Test
    void toolDetailsRenderBelowToolHeader() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(3);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiToolBlock("tool:1", "msg_1", "toolu_1", "bash", TuiToolState.DONE, "Bash", "stdout: ok\nexit 0", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 7), "");

        assertEquals("tool done bash: Bash", lines.get(0));
        assertEquals("  stdout: ok", lines.get(1));
        assertEquals("  exit 0", lines.get(2));
    }

    @Test
    void slashOverlayRendersAboveInputLineAndKeepsFixedHeight() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(
            view,
            screen,
            new TuiLayout(40, 7),
            "/",
            1,
            List.of("> /model", "  /thinking", "  /compact")
        );

        assertEquals(7, lines.size());
        assertEquals("> /model", lines.get(0));
        assertEquals("  /thinking", lines.get(1));
        assertEquals("  /compact", lines.get(2));
        assertInputBorder(lines.get(3), 40);
        assertInputContent(lines.get(4), "> /|CURSOR|" + INPUT_CURSOR);
        assertInputBorder(lines.get(5), 40);
        assertTrue(lines.get(6).contains("ses_1"));
    }

    private void assertInputBorder(String line, int width) {
        assertEquals(width, AnsiWidth.displayWidth(line));
        assertTrue(line.contains("─"));
    }

    private void assertInputContent(String line, String content) {
        assertEquals(INPUT_BACKGROUND + content + ANSI_RESET, line);
    }
}
