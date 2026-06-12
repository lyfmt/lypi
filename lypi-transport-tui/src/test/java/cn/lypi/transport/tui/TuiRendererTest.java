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
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";

    @Test
    void rendersVisibleTranscriptStatusAndInput() {
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
    void statusBarDoesNotRenderApplicationScrollbackCounter() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        screen.setTranscript(List.of("old", "current"));
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiMessageBlock("b1", "m1", "assistant", "old", false),
                new TuiMessageBlock("b2", "m2", "assistant", "current", false)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(80, 3), "");

        assertFalse(lines.getLast().contains("scroll +"));
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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 7), "");

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

        List<String> lines = renderer.render(view, screen, new TuiLayout(40, 6), "");

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

        assertInputBorder(lines.get(0), 30);
        assertInputContent(lines.get(lines.size() - 2), "> alpha |CURSOR|" + INPUT_CURSOR + "beta");
    }

    @Test
    void emptyTranscriptStillAnchorsInputBlockAtBottomOfViewport() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(1);
        TuiViewModel view = new TuiViewModel(
            List.of(),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.render(view, screen, new TuiLayout(20, 6), "", 0);

        assertEquals(6, lines.size());
        assertEquals("", lines.get(0));
        assertEquals("", lines.get(1));
        assertInputBorder(lines.get(2), 20);
        assertInputContent(lines.get(3), "> |CURSOR|" + INPUT_CURSOR);
        assertInputBorder(lines.get(4), 20);
        assertTrue(lines.get(5).contains("ses_1"));
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
    void longInputSoftWrapsInsideBottomInputBlockWithVisibleTranscript() {
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
        assertFalse(lines.contains("line1"));
        assertInputBorder(lines.get(lines.size() - 5), 8);
        assertEquals("\033[48;5;236m> abcde\033[0m", lines.get(lines.size() - 4));
        assertEquals("\033[48;5;236mfghij|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(lines.size() - 3));
        assertInputBorder(lines.get(lines.size() - 2), 8);
        assertTrue(lines.getLast().contains("ses_1"));
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
    void inputViewportShowsLatestRowsWhileKeepingTranscriptViewportFixed() {
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
        assertInputBorder(lines.get(lines.size() - 6), 10);
        assertEquals("\033[48;5;236mtwo\033[0m", lines.get(lines.size() - 5));
        assertEquals("\033[48;5;236mthree\033[0m", lines.get(lines.size() - 4));
        assertEquals("\033[48;5;236mfour|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(lines.size() - 3));
        assertInputBorder(lines.get(lines.size() - 2), 10);
    }

    @Test
    void inputBlockCanUseFullTerminalHeightWhenDraftHasManyRows() {
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
        assertInputBorder(lines.get(lines.size() - 3), 10);
        assertEquals("\033[48;5;236mfour|CURSOR|" + INPUT_CURSOR + "\033[0m", lines.get(lines.size() - 2));
        assertTrue(lines.getLast().contains("ses_1"));
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

        assertEquals("", withoutRuntimeLines.get(0));
        assertEquals("line1", withoutRuntimeLines.get(1));
        assertEquals("line2", withoutRuntimeLines.get(2));
        assertEquals("line3", withoutRuntimeLines.get(3));
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

        assertEquals("done $ Bash", lines.get(0));
        assertEquals("  stdout: ok", lines.get(1));
        assertEquals("  exit 0", lines.get(2));
    }

    @Test
    void bashToolCollapsedShowsCommandStatusSummaryAndTailPreview() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(20);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiToolBlock(
                "tool:1",
                "msg_1",
                "toolu_1",
                "bash",
                TuiToolState.FAILED,
                "mvn test",
                "stdout: line 1\nstdout: line 2\nstdout: line 3\nstdout: line 4\nstdout: line 5\nstdout: line 6\nexit 1\nBUILD FAILURE",
                false
            )),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.renderFrame(view, screen, new TuiLayout(80, 30), "", -1, List.of(), false).lines();

        assertTrue(lines.contains("failed $ mvn test"));
        assertTrue(lines.contains("  exit 1"));
        assertFalse(lines.contains("  stdout: line 1"));
        assertTrue(lines.contains("  stdout: line 6"));
    }

    @Test
    void readEditAndUnknownToolsUseStructuredTitles() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(20);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiToolBlock("tool:read", "msg_1", "toolu_read", "read", TuiToolState.DONE, "src/App.java:1-80", "1 | class App {}\n2 |", false),
                new TuiToolBlock("tool:edit", "msg_1", "toolu_edit", "edit", TuiToolState.DONE, "src/App.java", "@@ -1 +1 @@\n-old\n+new", false),
                new TuiToolBlock("tool:custom", "msg_1", "toolu_custom", "custom_tool", TuiToolState.RUNNING, "payload", "{\"key\":\"value\"}", true)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.renderFrame(view, screen, new TuiLayout(80, 30), "", -1, List.of(), false).lines();

        assertTrue(lines.contains("tools: read x1 (Ctrl+O details)"));
        assertTrue(lines.contains("done edit src/App.java +1 -1"));
        assertTrue(lines.contains("running custom_tool payload"));
    }

    @Test
    void readToolOutputNeverShowsFileContentAndExpandsToInvocationOnly() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(30);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiToolBlock(
                "tool:1",
                "msg_1",
                "toolu_1",
                "read",
                TuiToolState.DONE,
                "src/Large.java:1-20",
                String.join("\n", java.util.stream.IntStream.rangeClosed(1, 12)
                    .mapToObj(index -> index + " | line " + index)
                    .toList()),
                false
            )),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> collapsed = renderer.renderFrame(view, screen, new TuiLayout(80, 30), "", -1, List.of(), false).lines();
        List<String> expanded = renderer.renderFrame(view, screen, new TuiLayout(80, 30), "", -1, List.of(), true).lines();

        assertFalse(collapsed.contains("  1 | line 1"));
        assertFalse(collapsed.contains("  11 | line 11"));
        assertTrue(collapsed.contains("tools: read x1 (Ctrl+O details)"));
        assertTrue(expanded.contains("done read src/Large.java:1-20"));
        assertFalse(expanded.contains("  1 | line 1"));
        assertFalse(expanded.contains("  11 | line 11"));
    }

    @Test
    void writeToolStillShowsContentPreview() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(20);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiToolBlock(
                "tool:write",
                "msg_1",
                "toolu_write",
                "write",
                TuiToolState.DONE,
                "src/App.java",
                "class App {}\n",
                false
            )),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> lines = renderer.renderFrame(view, screen, new TuiLayout(80, 20), "", -1, List.of(), false).lines();

        assertTrue(lines.contains("done write src/App.java"));
        assertTrue(lines.contains("  class App {}"));
    }

    @Test
    void searchToolsCollapseToCountsAndExpandToInvocationOnly() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(20);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiToolBlock(
                    "tool:glob",
                    "msg_1",
                    "toolu_glob",
                    "glob",
                    TuiToolState.DONE,
                    "{path=., pattern=**/*}",
                    "matched AGENTS.md\nmatched Solution.java\nmatched application.yml",
                    false
                ),
                new TuiToolBlock(
                    "tool:read",
                    "msg_1",
                    "toolu_read",
                    "read",
                    TuiToolState.DONE,
                    "AGENTS.md:1-200",
                    "File: AGENTS.md\n1 | secret content",
                    false
                ),
                new TuiToolBlock(
                    "tool:grep",
                    "msg_1",
                    "toolu_grep",
                    "grep",
                    TuiToolState.RUNNING,
                    "{pattern=apiKey, path=.}",
                    "matched application.yml",
                    true
                )
            ),
            new StatusBarState("ses_1", "gpt-5.4", "execute", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> collapsed = renderer.renderFrame(view, screen, new TuiLayout(80, 20), "", -1, List.of(), false).lines();
        List<String> expanded = renderer.renderFrame(view, screen, new TuiLayout(80, 20), "", -1, List.of(), true).lines();

        assertTrue(collapsed.contains("tools: glob x1, read x1, grep x1 (Ctrl+O details)"));
        assertFalse(collapsed.contains("  matched AGENTS.md"));
        assertFalse(collapsed.contains("File: AGENTS.md"));
        assertTrue(expanded.contains("done glob {path=., pattern=**/*}"));
        assertTrue(expanded.contains("done read AGENTS.md:1-200"));
        assertTrue(expanded.contains("running grep {pattern=apiKey, path=.}"));
        assertFalse(expanded.contains("  matched AGENTS.md"));
        assertFalse(expanded.contains("File: AGENTS.md"));
    }

    @Test
    void slashOverlayRendersBelowInputBlock() {
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
        assertInputBorder(lines.get(0), 40);
        assertInputContent(lines.get(1), "> /|CURSOR|" + INPUT_CURSOR);
        assertInputBorder(lines.get(2), 40);
        assertTrue(lines.get(3).startsWith("> /model"));
        assertTrue(lines.get(4).contains("  /thinking"));
        assertTrue(lines.get(5).contains("  /compact"));
        assertTrue(lines.get(6).contains("ses_1"));
    }

    @Test
    void overlayRendersBelowInputWithTranscriptContent() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen = new TuiScreen(10);
        TuiViewModel view = new TuiViewModel(
            List.of(
                new TuiMessageBlock("b1", "m1", "assistant", "hello", false),
                new TuiMessageBlock("b2", "m2", "assistant", "world", false)
            ),
            new StatusBarState("ses_1", "gpt-5.4", "ready", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> withOverlay = renderer.render(
            view, screen, new TuiLayout(40, 10), "/", 1,
            List.of("> /model", "  /thinking", "  /compact")
        );

        List<String> withoutOverlay = renderer.render(
            view, screen, new TuiLayout(40, 10), "/", 1
        );

        assertEquals(withoutOverlay.size(), withOverlay.size());
        int overlayIndex = -1;
        int inputBorderIndex = -1;
        for (int i = 0; i < withOverlay.size(); i++) {
            if (withOverlay.get(i).contains("> /model")) {
                overlayIndex = i;
            }
            if (withOverlay.get(i).contains("─")) {
                inputBorderIndex = i;
            }
        }
        assertTrue(overlayIndex >= 0, "overlay should be present");
        assertTrue(inputBorderIndex >= 0, "input border should be present");
        assertTrue(overlayIndex > inputBorderIndex, "overlay should be below input block");
    }

    @Test
    void emptyOverlayProducesSameOutputAsNoOverlay() {
        TuiRenderer renderer = new TuiRenderer();
        TuiScreen screen1 = new TuiScreen(5);
        TuiScreen screen2 = new TuiScreen(5);
        TuiViewModel view = new TuiViewModel(
            List.of(new TuiMessageBlock("b1", "m1", "assistant", "test", false)),
            new StatusBarState("ses_1", "gpt-5.4", "ready", "default"),
            List.of(),
            Optional.empty(),
            Optional.empty()
        );

        List<String> withEmptyOverlay = renderer.render(
            view, screen1, new TuiLayout(40, 8), "hello", 5, List.of()
        );
        List<String> withoutOverlay = renderer.render(
            view, screen2, new TuiLayout(40, 8), "hello", 5
        );

        assertEquals(withoutOverlay, withEmptyOverlay);
    }

    private void assertInputBorder(String line, int width) {
        assertEquals(width, AnsiWidth.displayWidth(line));
        assertTrue(line.contains("─"));
    }

    private void assertInputContent(String line, String content) {
        assertEquals(INPUT_BACKGROUND + content + ANSI_RESET, line);
    }
}
