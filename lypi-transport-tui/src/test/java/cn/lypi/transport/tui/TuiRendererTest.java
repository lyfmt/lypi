package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiMessageBlock;
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
        assertTrue(lines.get(2).contains("tool"));
        assertEquals("> draft", lines.get(3));
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

        assertTrue(lines.get(1).contains("tool"));
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

        assertEquals("> alpha |CURSOR|beta", lines.get(2));
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

        assertEquals("> …efgh|CURSOR|", lines.get(2));
    }
}
