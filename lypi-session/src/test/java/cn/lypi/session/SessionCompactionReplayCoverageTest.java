package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionCompactionReplayCoverageTest {
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void repeatedCompactionsApplyOnlyLatestCompaction() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(messageEntry("root", null, "msg-root", "root"));
        manager.append(messageEntry("after-first-kept", "root", "msg-first-kept", "first kept"));
        manager.append(compaction("compact-first", "after-first-kept", "first summary", "after-first-kept"));
        manager.append(messageEntry("between", "compact-first", "msg-between", "between"));
        manager.append(messageEntry("after-second-kept", "between", "msg-second-kept", "second kept"));
        manager.append(compaction("compact-second", "after-second-kept", "second summary", "after-second-kept"));
        manager.append(messageEntry("tail", "compact-second", "msg-tail", "tail"));

        SessionContext context = manager.context("tail");

        assertThat(context.appliedCompactionEntryIds()).containsExactly("compact-second");
        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact-second", "msg-second-kept", "msg-tail");
        assertThat(context.messages())
            .extracting(block -> block.content().getFirst().text())
            .doesNotContain("first summary", "root", "first kept", "between");
    }

    @Test
    void forkedLeafCompactionsDoNotPolluteSiblingLeafReplay() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(messageEntry("root", null, "msg-root", "root"));
        manager.append(messageEntry("left-old", "root", "msg-left-old", "left old"));
        manager.append(messageEntry("left-kept", "left-old", "msg-left-kept", "left kept"));
        manager.append(compaction("compact-left", "left-kept", "left summary", "left-kept"));
        manager.append(messageEntry("left-tail", "compact-left", "msg-left-tail", "left tail"));
        manager.append(messageEntry("right-old", "root", "msg-right-old", "right old"));
        manager.append(messageEntry("right-kept", "right-old", "msg-right-kept", "right kept"));
        manager.append(compaction("compact-right", "right-kept", "right summary", "right-kept"));
        manager.append(messageEntry("right-tail", "compact-right", "msg-right-tail", "right tail"));

        SessionContext left = manager.context("left-tail");
        SessionContext right = manager.context("right-tail");

        assertThat(left.appliedCompactionEntryIds()).containsExactly("compact-left");
        assertThat(left.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact-left", "msg-left-kept", "msg-left-tail");
        assertThat(left.messages())
            .extracting(block -> block.content().getFirst().text())
            .doesNotContain("right summary", "right old", "right kept", "right tail");

        assertThat(right.appliedCompactionEntryIds()).containsExactly("compact-right");
        assertThat(right.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact-right", "msg-right-kept", "msg-right-tail");
        assertThat(right.messages())
            .extracting(block -> block.content().getFirst().text())
            .doesNotContain("left summary", "left old", "left kept", "left tail");
    }

    @Test
    void reopenRestoresCompactionModelThinkingModeAndPermission() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new ModelChangeEntry(
            "model",
            null,
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.LOW),
            "select provider",
            NOW
        ));
        manager.append(new ThinkingChangeEntry("thinking", "model", ThinkingLevel.HIGH, "raise reasoning", NOW));
        manager.append(new ModeChangeEntry("mode", "thinking", AgentMode.PLAN, "plan first", NOW));
        manager.append(new PermissionModeChangeEntry("permission", "mode", PermissionMode.PLAN, "read-only plan", NOW));
        manager.append(messageEntry("old", "permission", "msg-old", "old"));
        manager.append(messageEntry("kept", "old", "msg-kept", "kept"));
        manager.append(compaction("compact", "kept", "persisted summary", "kept"));
        manager.append(messageEntry("tail", "compact", "msg-tail", "tail"));

        SessionManager reopened = new SessionManagerImpl(tempDir);
        reopened.openOrCreate("ses_main");
        SessionContext context = reopened.context("tail");

        assertThat(context.appliedCompactionEntryIds()).containsExactly("compact");
        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact", "msg-kept", "msg-tail");
        assertThat(context.messages().getFirst().content().getFirst().text()).isEqualTo("persisted summary");
        assertThat(context.model()).isEqualTo(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH));
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(context.branchEntryIds())
            .containsExactly("model", "thinking", "mode", "permission", "old", "kept", "compact", "tail");
    }

    private static MessageEntry messageEntry(String entryId, String parentId, String messageId, String text) {
        return new MessageEntry(entryId, parentId, textMessage(messageId, text), NOW);
    }

    private static CompactionEntry compaction(String entryId, String parentId, String summary, String firstKeptEntryId) {
        return new CompactionEntry(entryId, parentId, summary, firstKeptEntryId, 100, 20, CompactionKind.SESSION, NOW);
    }

    private static AgentMessage textMessage(String id, String text) {
        return new AgentMessage(
            id,
            MessageRole.USER,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }
}
