package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiRendererTest {
    @Test
    void rendersTranscriptStatusAndInputWithinFixedHeight() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "hello world", false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "tool:running"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(12, 4), "draft");

        assertEquals(4, lines.size());
        assertEquals("hello world", lines.get(0));
        assertEquals("", lines.get(1));
        assertEquals("\033[48;5;236m> draft\033[0m", lines.get(2));
        assertTrue(lines.get(3).contains("tool"));
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

        assertTrue(lines.get(2).contains("tool"));
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

        assertEquals("ses_1 gpt-5.4 EXECUTE DEFAULT_EXECUTE", lines.get(2));
        assertFalse(lines.get(2).contains("cwd:"));
        assertFalse(lines.get(2).contains("leaf:"));
        assertFalse(lines.get(2).contains("ctx:"));
        assertFalse(lines.get(2).contains("tool:interruptible"));
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(20, 4), "");

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

        assertEquals("\033[48;5;236m> alpha |CURSOR|beta\033[0m", lines.get(1));
    }

    @Test
    void inputLineKeepsCursorMarkerAfterNarrowTruncation() {
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

        assertEquals("\033[48;5;236m> …efgh|CURSOR|\033[0m", lines.get(1));
    }

    @Test
    void permissionPromptIsRenderedInTranscriptArea() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.of(new PermissionPromptView("toolu_1", "Need approval", "bash:npm test", "allow_once", "cancel")),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 4), "");

        assertEquals("permission toolu_1: Need approval", lines.get(0));
        assertEquals("rule: bash:npm test", lines.get(1));
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 5), "");

        assertEquals("line2", lines.get(0));
        assertEquals("line3", lines.get(1));
        assertEquals("· retrying attempt 2 rate limit", lines.get(2));
        assertTrue(lines.get(3).startsWith("\033[48;5;236m> "));
        assertTrue(lines.get(4).contains("ses_1"));

        TuiScreen screenWithoutRuntime = new TuiScreen(3);
        TuiViewModel withoutRuntime = new TuiViewModel(
            view.blocks(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );
        List<String> withoutRuntimeLines = renderer.render(withoutRuntime, screenWithoutRuntime, new TuiLayout(40, 5), "");

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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 5), "");

        assertEquals("tool done bash: Bash", lines.get(0));
        assertEquals("  stdout: ok", lines.get(1));
        assertEquals("  exit 0", lines.get(2));
    }
}
