package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.tui.BranchSummaryOffer;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ResumeOverlayControllerTest {
    @Test
    void selectingUserEntryResumesParentLeafAndRestoresDraftText() {
        List<String> submittedResumes = new ArrayList<>();
        List<String> restoredDrafts = new ArrayList<>();
        ResumeOverlayController overlay = new ResumeOverlayController(
            resumeControllerWithTree(userEntry("user_old", "assistant_parent", "edit this again")),
            ignored -> {
            },
            (sessionId, leafId) -> submittedResumes.add(sessionId + ":" + leafId),
            restoredDrafts::add
        );

        overlay.openSessions("ses_old", 6);
        overlay.handleKey(TerminalKey.ENTER, 6, () -> {
        });
        overlay.handleKey(TerminalKey.ENTER, 6, () -> {
        });

        assertEquals(List.of("ses_old:assistant_parent"), submittedResumes);
        assertEquals(List.of("edit this again"), restoredDrafts);
        assertFalse(overlay.open());
    }

    @Test
    void acceptedBranchSummaryResumesReturnedSummaryLeafAndHidesSummaryTranscript() {
        List<String> submittedResumes = new ArrayList<>();
        List<String> summaryTargets = new ArrayList<>();
        List<SessionRuntimeState> states = new ArrayList<>();
        AtomicInteger renders = new AtomicInteger();
        ResumeOverlayController overlay = new ResumeOverlayController(
            new ResumeSessionController() {
                @Override
                public List<SessionResumeInfo> sessions() {
                    return ResumeOverlayControllerTest.sessions("assistant_old");
                }

                @Override
                public SessionBranchTreeView tree(String sessionId) {
                    return new SessionBranchTreeView(
                        sessionId,
                        "assistant_old",
                        List.of(new SessionTreeNodeView(assistantEntry("assistant_old", "user_old", "old answer"), List.of()))
                    );
                }

                @Override
                public Optional<BranchSummaryOffer> branchSummaryOffer(String sessionId, String targetLeafId) {
                    return Optional.of(new BranchSummaryOffer(sessionId, "current_leaf", targetLeafId, "common_leaf", 2));
                }

                @Override
                public SessionRuntimeState resume(String sessionId, String leafId) {
                    return runtimeState(sessionId, leafId);
                }

                @Override
                public SessionRuntimeState resumeWithBranchSummary(String sessionId, String targetLeafId) {
                    summaryTargets.add(targetLeafId);
                    return runtimeState(
                        sessionId,
                        "summary_leaf",
                        List.of(new AgentMessage(
                            "summary_msg",
                            MessageRole.SYSTEM_LOCAL,
                            MessageKind.SUMMARY,
                            List.of(new TextContentBlock("hidden summary")),
                            Instant.EPOCH,
                            Optional.empty(),
                            Optional.empty()
                        ))
                    );
                }
            },
            states::add,
            (sessionId, leafId) -> submittedResumes.add(sessionId + ":" + leafId),
            ignored -> {
            }
        );

        overlay.openSessions("ses_old", 6);
        overlay.handleKey(TerminalKey.ENTER, 6, renders::incrementAndGet);
        overlay.handleKey(TerminalKey.ENTER, 6, renders::incrementAndGet);

        assertTrue(overlay.overlayLines(80).getFirst().contains("Summarize abandoned branch"));

        overlay.handleText("y", renders::incrementAndGet);

        assertEquals(List.of("assistant_old"), summaryTargets);
        assertEquals(List.of("ses_old:summary_leaf"), submittedResumes);
        assertEquals(List.of("Branch summary saved."), overlay.overlayLines(80));
        assertTrue(renders.get() >= 2);
        assertEquals(1, states.size());
        assertTrue(states.getFirst().transcript().stream().noneMatch(message -> message.kind() == MessageKind.SUMMARY));
    }

    private static ResumeSessionController resumeControllerWithTree(MessageEntry entry) {
        return new ResumeSessionController() {
            @Override
            public List<SessionResumeInfo> sessions() {
                return ResumeOverlayControllerTest.sessions(entry.id());
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

    private static List<SessionResumeInfo> sessions(String leafId) {
        return List.of(new SessionResumeInfo(
            Path.of("/tmp/ses_old.jsonl"),
            "ses_old",
            Path.of("/tmp/project"),
            Optional.empty(),
            leafId,
            Instant.EPOCH,
            Instant.EPOCH,
            1,
            "old session",
            "old session"
        ));
    }

    private static MessageEntry userEntry(String id, String parentId, String text) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                "msg_" + id,
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock(text)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            ),
            Instant.EPOCH
        );
    }

    private static MessageEntry assistantEntry(String id, String parentId, String text) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                "msg_" + id,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                List.of(new TextContentBlock(text)),
                Instant.EPOCH,
                Optional.empty(),
                Optional.empty()
            ),
            Instant.EPOCH
        );
    }

    private static SessionRuntimeState runtimeState(String sessionId, String leafId) {
        return runtimeState(sessionId, leafId, List.of());
    }

    private static SessionRuntimeState runtimeState(String sessionId, String leafId, List<AgentMessage> transcript) {
        return new SessionRuntimeState(
            sessionId,
            Path.of("."),
            leafId,
            new cn.lypi.contracts.model.ModelSelection("openai", "gpt-5.4", cn.lypi.contracts.model.ThinkingLevel.MEDIUM),
            cn.lypi.contracts.model.ThinkingLevel.MEDIUM,
            cn.lypi.contracts.security.AgentMode.EXECUTE,
            cn.lypi.contracts.security.PermissionMode.DEFAULT_EXECUTE,
            new cn.lypi.contracts.context.ContextBudget(0, 128_000, 100_000, 8_192, 16_384, 0L, 0L, java.math.BigDecimal.ZERO),
            transcript,
            false,
            false,
            false,
            false
        );
    }
}
