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
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiRendererTest {
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(12, 4), "draft");

        assertEquals(3, lines.size());
        assertEquals("hello world", lines.get(0));
        assertEquals("\033[48;5;236m> draft\033[0m", lines.get(lines.size() - 2));
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(20, 4), "");

        assertEquals("Done", lines.get(0));
        assertEquals("[x] task", lines.get(1));
    }

    @Test
    void rendersUserAndThinkingBlocksWithRoleStyles() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(3);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiMessageBlock("u1", "m1", "user", "请修复 TUI", false),
                new TuiThinkingBlock("t1", "m2", "分析路径", false, false),
                new TuiMessageBlock("a1", "m2", "assistant", "已处理", false)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 5), "");

        assertEquals("\033[38;5;81muser: 请修复 TUI\033[0m", lines.get(0));
        assertEquals("\033[38;5;244mthinking: 分析路径\033[0m", lines.get(1));
        assertEquals("已处理", lines.get(2));
    }

    @Test
    void rendersMultilineThinkingWithoutEmbeddedNewlines() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(2);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiThinkingBlock("t1", "m1", "第一行\n第二行", false, false)),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 4), "");

        assertEquals("\033[38;5;244mthinking: 第一行\033[0m", lines.get(0));
        assertEquals("\033[38;5;244m          第二行\033[0m", lines.get(1));
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

        assertEquals("\033[48;5;236m> alpha |CURSOR|beta\033[0m", lines.get(lines.size() - 2));
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

        assertEquals("\033[48;5;236m> …efgh|CURSOR|\033[0m", lines.get(lines.size() - 2));
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 7), "");

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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 6), "");

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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 5), "");

        assertEquals("line1", lines.get(0));
        assertEquals("line2", lines.get(1));
        assertEquals("line3", lines.get(2));
        assertEquals("· retrying attempt 2 rate limit", lines.get(3));
        assertTrue(lines.get(lines.size() - 2).startsWith("\033[48;5;236m> "));
        assertTrue(lines.getLast().contains("ses_1"));

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
            new TuiLayout(40, 5),
            "/",
            1,
            List.of("> /model", "  /thinking", "  /compact")
        );

        assertEquals(5, lines.size());
        assertEquals("> /model", lines.get(0));
        assertEquals("  /thinking", lines.get(1));
        assertEquals("\033[48;5;236m> /|CURSOR|\033[0m", lines.get(3));
        assertTrue(lines.get(4).contains("ses_1"));
    }
}
