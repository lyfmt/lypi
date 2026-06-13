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
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.LabelEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionManagerReplayTest {
    private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void contextReturnsDefaultStateForEmptySession() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");

        SessionContext context = manager.context(null);

        assertThat(context.messages()).isEmpty();
        assertThat(context.branchEntryIds()).isEmpty();
        assertThat(context.appliedCompactionEntryIds()).isEmpty();
        assertThat(context.model()).isEqualTo(new ModelSelection("default", "default", ThinkingLevel.MEDIUM));
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(context.mode()).isEqualTo(AgentMode.EXECUTE);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void emptySessionUsesConfiguredInitialState() {
        SessionManager manager = new SessionManagerImpl(
            tempDir,
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
        manager.openOrCreate("ses_main");

        SessionContext context = manager.context(null);

        assertThat(context.model()).isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM));
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(context.mode()).isEqualTo(AgentMode.EXECUTE);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void reopenedSessionUsesCurrentConfiguredBaselineWhenBranchHasNoOverrides() {
        SessionManager firstManager = new SessionManagerImpl(
            tempDir,
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
        SessionHandle firstHandle = firstManager.openOrCreate("ses_main");

        SessionManager reopenedWithDifferentDefaults = new SessionManagerImpl(
            tempDir,
            new ModelSelection("anthropic", "claude-sonnet", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        );
        SessionHandle reopened = reopenedWithDifferentDefaults.openOrCreate("ses_main");

        assertThat(reopened.leafId()).isEqualTo(firstHandle.leafId());
        SessionContext context = reopenedWithDifferentDefaults.context(reopened.leafId());
        assertThat(context.model()).isEqualTo(new ModelSelection("anthropic", "claude-sonnet", ThinkingLevel.HIGH));
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void openOrCreatePreservesCurrentLeafWhenSessionIsAlreadyOpen() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, textMessage("msg-root", "root"), NOW));
        manager.append(new MessageEntry("left", "root", textMessage("msg-left", "left"), NOW.plusSeconds(1)));
        manager.switchLeaf(null);

        SessionHandle reopened = manager.openOrCreate("ses_main");

        assertThat(reopened.leafId()).isNull();
        assertThat(manager.currentView().leafId()).isNull();
    }

    @Test
    void legacySessionWithoutInitialStateUsesCurrentConfiguredBaseline() {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(new SessionHeader("session", 1, "ses_legacy", tempDir, Optional.empty(), NOW));
        SessionManager manager = new SessionManagerImpl(
            tempDir,
            new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        );
        manager.openOrCreate("ses_legacy");

        SessionContext context = manager.context(null);

        assertThat(context.model()).isEqualTo(new ModelSelection("openai", "gpt-5-mini", ThinkingLevel.MEDIUM));
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void contextProjectsMessagesCustomMessagesAndBranchSummaries() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("entry-user", null, textMessage("msg-user", "hello"), NOW));
        manager.append(new CustomEntry("entry-custom", "entry-user", "demo.extension", Map.of("enabled", true), NOW));
        manager.append(new CustomMessageEntry("entry-custom-message", "entry-custom", "local hint", NOW));
        manager.append(new BranchSummaryEntry("entry-branch", "entry-custom-message", "entry-old-leaf", "branch summary", NOW));
        manager.append(new MessageEntry("entry-after", "entry-branch", textMessage("msg-after", "after"), NOW));

        SessionContext context = manager.context("entry-after");

        assertThat(context.branchEntryIds()).containsExactly(
            "entry-user",
            "entry-custom",
            "entry-custom-message",
            "entry-branch",
            "entry-after"
        );
        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-user", "custom-message-entry-custom-message", "branch-summary-entry-branch", "msg-after");
        assertThat(context.messages().get(1).role()).isEqualTo(MessageRole.SYSTEM_LOCAL);
        assertThat(context.messages().get(1).kind()).isEqualTo(MessageKind.TEXT);
        assertThat(context.messages().get(2).kind()).isEqualTo(MessageKind.SUMMARY);
        assertThat(manager.transcript("entry-after")).isEqualTo(context.messages());
    }

    @Test
    void contextRestoresLatestModelThinkingModeAndPermissionMode() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new ModelChangeEntry(
            "model-old",
            null,
            new ModelSelection("openai", "gpt-test-old", ThinkingLevel.LOW),
            "old",
            NOW
        ));
        manager.append(new ThinkingChangeEntry("thinking-low", "model-old", ThinkingLevel.LOW, "low", NOW));
        manager.append(new ModeChangeEntry("mode-execute", "thinking-low", AgentMode.EXECUTE, "execute", NOW));
        manager.append(new PermissionModeChangeEntry(
            "permission-default",
            "mode-execute",
            PermissionMode.DEFAULT_EXECUTE,
            "default",
            NOW
        ));
        manager.append(new ModelChangeEntry(
            "model-new",
            "permission-default",
            new ModelSelection("openai", "gpt-test-latest", ThinkingLevel.LOW),
            "latest",
            NOW
        ));
        manager.append(new ThinkingChangeEntry("thinking-high", "model-new", ThinkingLevel.HIGH, "high", NOW));
        manager.append(new ModeChangeEntry("mode-plan", "thinking-high", AgentMode.PLAN, "plan", NOW));
        manager.append(new PermissionModeChangeEntry(
            "permission-accept-edits",
            "mode-plan",
            PermissionMode.ACCEPT_EDITS,
            "accept edits",
            NOW
        ));

        SessionContext context = manager.context("permission-accept-edits");

        assertThat(context.model().modelId()).isEqualTo("gpt-test-latest");
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.model().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.ACCEPT_EDITS);
    }

    @Test
    void contextAppliesLatestCompactionAsReplayView() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("old", null, textMessage("msg-old", "old"), NOW));
        manager.append(new MessageEntry("kept", "old", textMessage("msg-kept", "kept"), NOW));
        manager.append(new CompactionEntry("compact", "kept", "summary", "kept", 100, 20, CompactionKind.SESSION, NOW));
        manager.append(new MessageEntry("after", "compact", textMessage("msg-after", "after"), NOW));

        SessionContext context = manager.context("after");

        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact", "msg-kept", "msg-after");
        assertThat(context.messages().getFirst().role()).isEqualTo(MessageRole.USER);
        assertThat(context.messages().getFirst().kind()).isEqualTo(MessageKind.SUMMARY);
        assertThat(context.appliedCompactionEntryIds()).containsExactly("compact");
        assertThat(context.messages()).extracting(AgentMessage::id).doesNotContain("msg-old");
        assertThat(manager.transcript("after")).isEqualTo(context.messages());
    }

    @Test
    void branchIsolationKeepsSiblingMessagesOutOfContext() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, textMessage("msg-root", "root"), NOW));
        manager.append(new MessageEntry("left", "root", textMessage("msg-left", "left"), NOW));
        manager.append(new MessageEntry("right", "root", textMessage("msg-right", "right"), NOW));

        assertThat(manager.context("left").messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-root", "msg-left");
        assertThat(manager.context("right").messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-root", "msg-right");
        assertThat(manager.branch("left")).extracting(entry -> entry.id()).containsExactly("root", "left");
        assertThat(manager.branch("right")).extracting(entry -> entry.id()).containsExactly("root", "right");
    }

    @Test
    void branchScopedConfigChangesOnlyApplyOnCurrentBranchPath() {
        SessionManager manager = new SessionManagerImpl(
            tempDir,
            new ModelSelection("openai", "gpt-baseline", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        );
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, textMessage("msg-root", "root"), NOW));
        manager.append(new ThinkingChangeEntry("left-thinking", "root", ThinkingLevel.HIGH, "left", NOW));
        manager.append(new MessageEntry("left", "left-thinking", textMessage("msg-left", "left"), NOW));
        manager.switchLeaf("root");
        manager.append(new MessageEntry("right", "root", textMessage("msg-right", "right"), NOW));

        SessionContext left = manager.context("left");
        SessionContext right = manager.context("right");

        assertThat(left.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(left.model()).isEqualTo(new ModelSelection("openai", "gpt-baseline", ThinkingLevel.HIGH));
        assertThat(right.thinkingLevel()).isEqualTo(ThinkingLevel.MEDIUM);
        assertThat(right.model()).isEqualTo(new ModelSelection("openai", "gpt-baseline", ThinkingLevel.MEDIUM));
    }

    @Test
    void compactionOnlyAppliesOnCurrentBranch() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, textMessage("msg-root", "root"), NOW));
        manager.append(new MessageEntry("old-left", "root", textMessage("msg-old-left", "old left"), NOW));
        manager.append(new CompactionEntry(
            "compact-left",
            "old-left",
            "left summary",
            "old-left",
            100,
            20,
            CompactionKind.SESSION,
            NOW
        ));
        manager.append(new MessageEntry("after-left", "compact-left", textMessage("msg-after-left", "after left"), NOW));
        manager.append(new MessageEntry("old-right", "root", textMessage("msg-old-right", "old right"), NOW));
        manager.append(new MessageEntry("after-right", "old-right", textMessage("msg-after-right", "after right"), NOW));

        assertThat(manager.context("after-left").appliedCompactionEntryIds()).containsExactly("compact-left");
        assertThat(manager.context("after-left").messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact-left", "msg-old-left", "msg-after-left");
        assertThat(manager.context("after-right").appliedCompactionEntryIds()).isEmpty();
        assertThat(manager.context("after-right").messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-root", "msg-old-right", "msg-after-right");
    }

    @Test
    void compactionWithMissingFirstKeptFallsBackToFullMessagesPlusSummary() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("old", null, textMessage("msg-old", "old"), NOW));
        manager.append(new CompactionEntry("compact", "old", "summary", "missing", 100, 20, CompactionKind.SESSION, NOW));
        manager.append(new MessageEntry("after", "compact", textMessage("msg-after", "after"), NOW));

        SessionContext context = manager.context("after");

        assertThat(context.appliedCompactionEntryIds()).containsExactly("compact");
        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact", "msg-old", "msg-after");
    }

    @Test
    void labelAndSessionInfoRemainInBranchWithoutAffectingReplay() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, textMessage("msg-root", "root"), NOW));
        manager.append(new LabelEntry("label", "root", "checkpoint", NOW));
        manager.append(new SessionInfoEntry("info", "label", Map.of("name", "demo"), NOW));
        manager.append(new MessageEntry("after", "info", textMessage("msg-after", "after"), NOW));

        assertThat(manager.branch("after")).extracting(entry -> entry.id()).containsExactly("root", "label", "info", "after");
        assertThat(manager.context("after").messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-root", "msg-after");
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
