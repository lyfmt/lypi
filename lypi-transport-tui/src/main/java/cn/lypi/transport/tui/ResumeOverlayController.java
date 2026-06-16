package cn.lypi.transport.tui;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tui.BranchSummaryOffer;
import cn.lypi.contracts.tui.ResumeSessionController;
import cn.lypi.contracts.tui.SessionResumeInfo;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class ResumeOverlayController {
    private final ResumeSessionController resumeController;
    private final Consumer<SessionRuntimeState> resumeStateConsumer;
    private final BiConsumer<String, String> resumeSubmitter;
    private final Consumer<String> draftRestorer;
    private ResumeSessionSelector resumeSessionSelector;
    private ResumeBranchTreeSelector resumeBranchTreeSelector;
    private PendingBranchSummaryResume pendingBranchSummaryResume;
    private String branchSummaryLine;
    private String resumeSessionId;

    ResumeOverlayController(
        ResumeSessionController resumeController,
        Consumer<SessionRuntimeState> resumeStateConsumer,
        BiConsumer<String, String> resumeSubmitter,
        Consumer<String> draftRestorer
    ) {
        this.resumeController = resumeController;
        this.resumeStateConsumer = resumeStateConsumer;
        this.resumeSubmitter = resumeSubmitter;
        this.draftRestorer = draftRestorer;
    }

    void openSessions(String currentSessionId, int maxVisible) {
        List<SessionResumeInfo> sessions = resumeController.sessions();
        java.nio.file.Path currentPath = sessions.stream()
            .filter(session -> session.sessionId().equals(currentSessionId))
            .map(SessionResumeInfo::path)
            .findFirst()
            .orElse(null);
        resumeSessionSelector = new ResumeSessionSelector(sessions, currentPath, maxVisible);
        resumeBranchTreeSelector = null;
        resumeSessionId = null;
    }

    boolean open() {
        return resumeSessionSelector != null || resumeBranchTreeSelector != null || pendingBranchSummaryResume != null;
    }

    boolean pendingBranchSummary() {
        return pendingBranchSummaryResume != null;
    }

    void clearTransientLineUnlessPendingSummary() {
        if (pendingBranchSummaryResume == null) {
            branchSummaryLine = null;
        }
    }

    void clearTransientLine() {
        branchSummaryLine = null;
    }

    List<String> overlayLines(int width) {
        if (pendingBranchSummaryResume != null) {
            BranchSummaryOffer offer = pendingBranchSummaryResume.offer();
            if (branchSummaryLine != null && !branchSummaryLine.isBlank()) {
                return List.of(branchSummaryLine);
            }
            return List.of(
                "Summarize abandoned branch before switching? [y/N]",
                offer.entriesToSummarize() + " entries will be summarized from the branch you are leaving."
            );
        }
        if (branchSummaryLine != null && !branchSummaryLine.isBlank()) {
            return List.of(branchSummaryLine);
        }
        if (resumeBranchTreeSelector != null) {
            return resumeBranchTreeSelector.render(width);
        }
        if (resumeSessionSelector != null) {
            return resumeSessionSelector.render(width);
        }
        return List.of();
    }

    void handleKey(TerminalKey key, int maxVisible, Runnable renderAction) {
        if (key == TerminalKey.ESC || key == TerminalKey.CTRL_C) {
            close();
            renderAction.run();
            return;
        }
        if (pendingBranchSummaryResume != null) {
            handleBranchSummaryConfirmationKey(key, renderAction);
            return;
        }
        if (resumeSessionSelector != null) {
            handleResumeSessionKey(key, maxVisible, renderAction);
            return;
        }
        handleResumeTreeKey(key, renderAction);
    }

    void handleText(String text, Runnable renderAction) {
        String answer = text == null ? "" : text.trim();
        if ("y".equalsIgnoreCase(answer)) {
            resumeWithBranchSummary(renderAction);
            return;
        }
        if ("n".equalsIgnoreCase(answer)) {
            PendingBranchSummaryResume pending = pendingBranchSummaryResume;
            resumeWithoutBranchSummary(pending.sessionId(), pending.target());
            close();
            renderAction.run();
            return;
        }
        renderAction.run();
    }

    private void handleResumeSessionKey(TerminalKey key, int maxVisible, Runnable renderAction) {
        if (key == TerminalKey.UP) {
            resumeSessionSelector.moveUp();
            renderAction.run();
            return;
        }
        if (key == TerminalKey.DOWN) {
            resumeSessionSelector.moveDown();
            renderAction.run();
            return;
        }
        if (key == TerminalKey.ENTER) {
            resumeSessionSelector.selectedSession().ifPresent(session -> {
                resumeSessionId = session.sessionId();
                resumeBranchTreeSelector = new ResumeBranchTreeSelector(
                    resumeController.tree(session.sessionId()).roots(),
                    session.leafId(),
                    maxVisible
                );
                resumeSessionSelector = null;
            });
            renderAction.run();
        }
    }

    private void handleResumeTreeKey(TerminalKey key, Runnable renderAction) {
        if (resumeBranchTreeSelector == null) {
            return;
        }
        if (key == TerminalKey.UP) {
            resumeBranchTreeSelector.moveUp();
            renderAction.run();
            return;
        }
        if (key == TerminalKey.DOWN) {
            resumeBranchTreeSelector.moveDown();
            renderAction.run();
            return;
        }
        if (key == TerminalKey.TAB) {
            resumeBranchTreeSelector.toggleUserOnly();
            renderAction.run();
            return;
        }
        if (key == TerminalKey.ENTER) {
            Optional<SessionEntry> selectedEntry = resumeBranchTreeSelector.selectedEntry();
            if (selectedEntry.isEmpty()) {
                close();
                renderAction.run();
                return;
            }
            ResumeTarget target = resumeTarget(selectedEntry.orElseThrow());
            Optional<BranchSummaryOffer> offer = resumeController.branchSummaryOffer(resumeSessionId, target.leafId());
            if (offer.isPresent()) {
                pendingBranchSummaryResume = new PendingBranchSummaryResume(resumeSessionId, target, offer.orElseThrow());
                resumeBranchTreeSelector = null;
                renderAction.run();
                return;
            }
            resumeWithoutBranchSummary(resumeSessionId, target);
            close();
            renderAction.run();
        }
    }

    private void handleBranchSummaryConfirmationKey(TerminalKey key, Runnable renderAction) {
        if (key == TerminalKey.ENTER) {
            PendingBranchSummaryResume pending = pendingBranchSummaryResume;
            resumeWithoutBranchSummary(pending.sessionId(), pending.target());
            close();
            renderAction.run();
        }
    }

    private void resumeWithBranchSummary(Runnable renderAction) {
        PendingBranchSummaryResume pending = pendingBranchSummaryResume;
        branchSummaryLine = "Summarizing abandoned branch...";
        renderAction.run();
        SessionRuntimeState resumed = resumeController.resumeWithBranchSummary(pending.sessionId(), pending.target().leafId());
        String resumedLeafId = resumed == null ? pending.target().leafId() : resumed.currentBranchLeafId();
        resumeSubmitter.accept(pending.sessionId(), resumedLeafId);
        pending.target().draftText().ifPresent(draftRestorer);
        if (resumeStateConsumer != null && resumed != null) {
            resumeStateConsumer.accept(withoutSummaryTranscript(resumed));
        }
        branchSummaryLine = "Branch summary saved.";
        close();
        renderAction.run();
    }

    private void resumeWithoutBranchSummary(String sessionId, ResumeTarget target) {
        SessionRuntimeState resumed = resumeController.resume(sessionId, target.leafId());
        resumeSubmitter.accept(sessionId, target.leafId());
        target.draftText().ifPresent(draftRestorer);
        if (resumeStateConsumer != null && resumed != null) {
            resumeStateConsumer.accept(resumed);
        }
    }

    private void close() {
        resumeSessionSelector = null;
        resumeBranchTreeSelector = null;
        pendingBranchSummaryResume = null;
        resumeSessionId = null;
    }

    private SessionRuntimeState withoutSummaryTranscript(SessionRuntimeState state) {
        List<AgentMessage> visibleTranscript = state.transcript().stream()
            .filter(message -> message.kind() != MessageKind.SUMMARY)
            .toList();
        return new SessionRuntimeState(
            state.sessionId(),
            state.cwd(),
            state.currentBranchLeafId(),
            state.model(),
            state.thinkingLevel(),
            state.agentMode(),
            state.permissionMode(),
            state.budget(),
            visibleTranscript,
            state.hasInterruptibleTool(),
            state.hasActiveTurn(),
            state.hasPendingPermission(),
            state.hasPendingInput()
        );
    }

    private ResumeTarget resumeTarget(SessionEntry entry) {
        if (entry instanceof MessageEntry messageEntry
            && messageEntry.message() != null
            && messageEntry.message().role() == cn.lypi.contracts.context.MessageRole.USER) {
            return new ResumeTarget(messageEntry.parentId(), userMessageText(messageEntry.message()));
        }
        return new ResumeTarget(entry.id(), Optional.empty());
    }

    private Optional<String> userMessageText(AgentMessage message) {
        if (message.content() == null) {
            return Optional.of("");
        }
        return Optional.of(message.content().stream()
            .filter(TextContentBlock.class::isInstance)
            .map(TextContentBlock.class::cast)
            .map(TextContentBlock::text)
            .filter(text -> text != null && !text.isBlank())
            .findFirst()
            .orElse(""));
    }

    private record ResumeTarget(String leafId, Optional<String> draftText) {
        private ResumeTarget {
            draftText = draftText == null ? Optional.empty() : draftText;
        }
    }

    private record PendingBranchSummaryResume(String sessionId, ResumeTarget target, BranchSummaryOffer offer) {
    }
}
