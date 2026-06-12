package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiViewModel;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillMention;
import cn.lypi.contracts.skill.SkillSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TuiInputLoopTest {
    private static final String INPUT_BACKGROUND = "\033[48;5;236m";
    private static final String INPUT_CURSOR = "\033[38;5;81m|\033[39m";
    private static final String ANSI_RESET = "\033[0m";

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
        assertEquals(inputContent("> |CURSOR|" + INPUT_CURSOR), inputLine(frames.getLast()));
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

        assertEquals(inputContent("> alpha be|CURSOR|" + INPUT_CURSOR + "ta"), inputLine(frames.getLast()));
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
        assertEquals(inputContent("> a|CURSOR|" + INPUT_CURSOR + "cd"), inputLine(frames.getLast()));
    }

    @Test
    void pasteWithNewlineKeepsDraftRendersRowsAndSubmitsOriginalText() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(20, 6)
        );

        loop.acceptPaste("alpha\nbeta");

        assertEquals("alpha\nbeta", loop.draft());
        assertEquals(List.of(inputContent("> alpha"), inputContent("beta|CURSOR|" + INPUT_CURSOR)), inputLines(frames.getLast()));

        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("alpha\nbeta"), submit.submitted);
        assertEquals("", loop.draft());
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
    void escapeInterruptsActiveToolWithoutRequiringInputFocus() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.setToolRunning(true);
        loop.acceptKey(TerminalKey.ESC);

        assertEquals(1, submit.interrupts);
        assertEquals(List.of("esc"), submit.interruptReasons);
    }

    @Test
    void escapeInterruptsActiveToolBeforeClosingSlashOverlay() {
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

        loop.acceptText("/");
        loop.setToolRunning(true);
        loop.acceptKey(TerminalKey.ESC);

        assertEquals("/", loop.draft());
        assertEquals(1, submit.interrupts);
        assertEquals(List.of("esc"), submit.interruptReasons);
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
            new TuiLayout(40, 9),
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

        loop.acceptKey(TerminalKey.UP);
        assertEquals("second", loop.draft());
        loop.acceptKey(TerminalKey.UP);
        assertEquals("first", loop.draft());
        loop.acceptKey(TerminalKey.DOWN);
        assertEquals("second", loop.draft());
        loop.acceptKey(TerminalKey.DOWN);
        assertEquals("", loop.draft());
    }

    @Test
    void upDoesNotReplaceNonEmptyDraftWithHistoryUntilHistoryNavigationStartsFromEmptyInput() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(submit, ignored -> {
        }, new TuiRenderer(), new TuiScreen(2), new TuiLayout(20, 4));

        loop.acceptText("first");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptText("draft");

        loop.acceptKey(TerminalKey.UP);
        assertEquals("draft", loop.draft());

        loop.acceptKey(TerminalKey.CTRL_U);
        loop.acceptKey(TerminalKey.UP);
        assertEquals("first", loop.draft());
        loop.acceptKey(TerminalKey.DOWN);
        assertEquals("", loop.draft());
    }

    @Test
    void upAndDownMoveCursorInsideMultilineDraftBeforeHistoryNavigation() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(20, 6)
        );

        loop.acceptText("history");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptPaste("abcde\nxy\n123456");
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.LEFT);
        loop.acceptKey(TerminalKey.LEFT);

        loop.acceptKey(TerminalKey.UP);
        assertEquals("abcde\nxy\n123456", loop.draft());
        assertEquals(8, loop.cursor());
        assertTrue(frames.getLast().contains(inputContent("xy|CURSOR|" + INPUT_CURSOR)));

        loop.acceptKey(TerminalKey.UP);
        assertEquals(3, loop.cursor());
        assertTrue(frames.getLast().contains(inputContent("> abc|CURSOR|" + INPUT_CURSOR + "de")));
    }

    @Test
    void upAndDownMoveCursorAcrossSoftWrappedInputRowsBeforeHistoryNavigation() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(2),
            new TuiLayout(8, 6)
        );

        loop.acceptText("history");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptText("abcdefghij");

        loop.acceptKey(TerminalKey.UP);

        assertEquals("abcdefghij", loop.draft());
        assertEquals(5, loop.cursor());
        assertTrue(frames.getLast().contains(inputContent("> abcde|CURSOR|" + INPUT_CURSOR)));

        loop.acceptKey(TerminalKey.DOWN);

        assertEquals(10, loop.cursor());
        assertTrue(frames.getLast().contains(inputContent("fghij|CURSOR|" + INPUT_CURSOR)));
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
            new TuiLayout(40, 9),
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

        assertEquals("/", loop.draft());
    }

    @Test
    void skillOverlayShowsCandidatesAcceptsSelectionAndSubmitsBinding() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(6),
            new TuiLayout(60, 9),
            null,
            () -> new SlashCommandPicker(List.of()),
            null,
            null,
            () -> skills("doc", "Document workflow")
        );

        loop.acceptText("use $d");

        assertTrue(frames.getLast().contains("> $doc"));

        loop.acceptKey(TerminalKey.TAB);
        assertEquals("use $doc", loop.draft());

        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("use $doc"), submit.submitted);
        assertEquals(List.of(new SkillMention("doc", Path.of("/tmp/doc/SKILL.md"))), submit.skillMentions.getFirst());
    }

    @Test
    void escapeClosesSkillOverlayAndSuppressesCurrentTokenBinding() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(4),
            new TuiLayout(60, 7),
            null,
            () -> new SlashCommandPicker(List.of()),
            null,
            null,
            () -> skills("doc", "Document workflow")
        );

        loop.acceptText("$doc");
        loop.acceptKey(TerminalKey.ESC);
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("$doc"), submit.submitted);
        assertTrue(submit.skillMentions.getFirst().isEmpty());
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
            new TuiLayout(40, 9),
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
    void resumeSlashOpensSessionThenBranchOverlayAndResumesSelectedLeaf() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        ResumeSessionController controller = new ResumeSessionController() {
            @Override
            public List<SessionResumeInfo> sessions() {
                return List.of(new SessionResumeInfo(
                    Path.of("/tmp/ses_old.jsonl"),
                    "ses_old",
                    Path.of("/tmp/project"),
                    Optional.empty(),
                    "leaf_old",
                    Instant.EPOCH,
                    Instant.EPOCH,
                    1,
                    "old session",
                    "old session"
                ));
            }

            @Override
            public SessionBranchTreeView tree(String sessionId) {
                MessageEntry root = new MessageEntry(
                    "leaf_old",
                    null,
                    new AgentMessage(
                        "msg_old",
                        MessageRole.USER,
                        MessageKind.TEXT,
                        List.of(new TextContentBlock("old prompt")),
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()
                    ),
                    Instant.EPOCH
                );
                MessageEntry assistant = new MessageEntry(
                    "assistant_old",
                    "leaf_old",
                    new AgentMessage(
                        "msg_assistant",
                        MessageRole.ASSISTANT,
                        MessageKind.TEXT,
                        List.of(new TextContentBlock("old answer")),
                        Instant.EPOCH,
                        Optional.empty(),
                        Optional.empty()
                    ),
                    Instant.EPOCH
                );
                return new SessionBranchTreeView(
                    sessionId,
                    "assistant_old",
                    List.of(new SessionTreeNodeView(root, List.of(new SessionTreeNodeView(assistant, List.of()))))
                );
            }

            @Override
            public SessionRuntimeState resume(String sessionId, String leafId) {
                return runtimeState(sessionId, leafId);
            }
        };
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(8),
            new TuiLayout(80, 10),
            null,
            () -> new SlashCommandPicker(List.of("/resume")),
            controller
        );

        loop.acceptText("/resume");
        loop.acceptKey(TerminalKey.ENTER);

        assertTrue(frames.getLast().contains("Resume Session (Current Folder)"));
        assertTrue(frames.getLast().contains("old session"));

        loop.acceptKey(TerminalKey.ENTER);
        assertTrue(frames.getLast().contains("user: old prompt"));
        assertTrue(frames.getLast().contains("assistant: old answer"));

        loop.acceptKey(TerminalKey.TAB);
        assertTrue(frames.getLast().contains("[user]"));
        assertTrue(frames.getLast().contains("user: old prompt"));
        assertTrue(!frames.getLast().contains("assistant: old answer"));

        loop.acceptKey(TerminalKey.ENTER);
        assertEquals(List.of("ses_old:null"), submit.resumes);
        assertEquals("old prompt", loop.draft());
    }

    @Test
    void resumeSelectingUserEntrySwitchesToParentLeafAndRestoresDraftText() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        ResumeSessionController controller = resumeControllerWithTree(
            "ses_old",
            new MessageEntry(
                "user_old",
                "assistant_parent",
                new AgentMessage(
                    "msg_old",
                    MessageRole.USER,
                    MessageKind.TEXT,
                    List.of(new TextContentBlock("edit this again")),
                    Instant.EPOCH,
                    Optional.empty(),
                    Optional.empty()
                ),
                Instant.EPOCH
            )
        );
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            ignored -> {
            },
            new TuiRenderer(),
            new TuiScreen(8),
            new TuiLayout(80, 10),
            null,
            () -> new SlashCommandPicker(List.of("/resume")),
            controller
        );

        loop.acceptText("/resume");
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptKey(TerminalKey.ENTER);
        loop.acceptKey(TerminalKey.ENTER);

        assertEquals(List.of("ses_old:assistant_parent"), submit.resumes);
        assertEquals("edit this again", loop.draft());
    }

    @Test
    void resumeControllerAddsResumeToSlashPickerCandidates() {
        RecordingSubmitHandler submit = new RecordingSubmitHandler();
        List<String> frames = new ArrayList<>();
        TuiInputLoop loop = new TuiInputLoop(
            submit,
            lines -> frames.add(String.join("\n", lines)),
            new TuiRenderer(),
            new TuiScreen(5),
            new TuiLayout(80, 8),
            null,
            () -> new SlashCommandPicker(List.of("/review")),
            emptyResumeController()
        );

        loop.acceptText("/r");

        assertTrue(frames.getLast().contains("/resume"));
        assertTrue(frames.getLast().contains("/review"));
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
        return inputLines(frame).getLast();
    }

    private static List<String> inputLines(String frame) {
        return frame.lines()
            .filter(line -> line.startsWith(INPUT_BACKGROUND))
            .toList();
    }

    private static String inputContent(String content) {
        return INPUT_BACKGROUND + content + ANSI_RESET;
    }

    private static ResumeSessionController emptyResumeController() {
        return new ResumeSessionController() {
            @Override
            public List<SessionResumeInfo> sessions() {
                return List.of();
            }

            @Override
            public SessionBranchTreeView tree(String sessionId) {
                return new SessionBranchTreeView(sessionId, null, List.of());
            }

            @Override
            public SessionRuntimeState resume(String sessionId, String leafId) {
                return runtimeState(sessionId, leafId);
            }
        };
    }

    private static ResumeSessionController resumeControllerWithTree(String sessionId, cn.lypi.contracts.session.SessionEntry entry) {
        return new ResumeSessionController() {
            @Override
            public List<SessionResumeInfo> sessions() {
                return List.of(new SessionResumeInfo(
                    Path.of("/tmp/" + sessionId + ".jsonl"),
                    sessionId,
                    Path.of("/tmp/project"),
                    Optional.empty(),
                    entry.id(),
                    Instant.EPOCH,
                    Instant.EPOCH,
                    1,
                    "old session",
                    "old session"
                ));
            }

            @Override
            public SessionBranchTreeView tree(String sessionId) {
                return new SessionBranchTreeView(sessionId, entry.id(), List.of(new SessionTreeNodeView(entry, List.of())));
            }

            @Override
            public SessionRuntimeState resume(String sessionId, String leafId) {
                return runtimeState(sessionId, leafId);
            }
        };
    }

    private static SessionRuntimeState runtimeState(String sessionId, String leafId) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("."),
            leafId,
            new cn.lypi.contracts.model.ModelSelection("openai", "gpt-5.4", cn.lypi.contracts.model.ThinkingLevel.MEDIUM),
            cn.lypi.contracts.model.ThinkingLevel.MEDIUM,
            cn.lypi.contracts.security.AgentMode.EXECUTE,
            cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE,
            new cn.lypi.contracts.context.ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, java.math.BigDecimal.ZERO),
            false,
            false,
            false,
            false
        );
    }

    private static final class RecordingSubmitHandler implements TuiSubmitHandler {
        private final List<String> submitted = new ArrayList<>();
        private final List<List<SkillMention>> skillMentions = new ArrayList<>();
        private final List<String> permissionOptions = new ArrayList<>();
        private final List<String> resumes = new ArrayList<>();
        private final List<String> interruptReasons = new ArrayList<>();
        private int interrupts;
        private int exits;

        @Override
        public void submitUserInput(String input) {
            submitted.add(input);
            skillMentions.add(List.of());
        }

        @Override
        public void submitUserInput(String input, List<SkillMention> skillMentions) {
            submitted.add(input);
            this.skillMentions.add(skillMentions);
        }

        @Override
        public void requestInterrupt(String reason) {
            interrupts++;
            interruptReasons.add(reason);
        }

        @Override
        public void requestExit(String reason) {
            exits++;
        }

        @Override
        public void submitPermissionOption(String requestId, String toolUseId, String optionId) {
            permissionOptions.add(requestId + ":" + toolUseId + ":" + optionId);
        }

        @Override
        public void resumeSession(String sessionId, String leafId) {
            resumes.add(sessionId + ":" + leafId);
        }
    }

    private static SkillIndex skills(String name, String description) {
        return new SkillIndex(List.of(new SkillDescriptor(
            name,
            description,
            SkillSource.PROJECT,
            Path.of("/tmp/" + name + "/SKILL.md"),
            List.of(),
            List.of(),
            "sha256:" + name
        )), List.of());
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
