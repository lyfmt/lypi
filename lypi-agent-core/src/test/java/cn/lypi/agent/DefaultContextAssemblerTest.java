package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.fixedResourceRuntime;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultContextAssemblerTest {
    @Test
    void usesConfiguredBudgetThresholds() {
        ContextBudgetEstimator estimator = new ContextBudgetEstimator(64, 10, 8, 4);

        var budget = estimator.estimate(List.of(userMessage(
            "msg-user",
            "01234567890123456789012345678901234567890123"
        )));

        assertThat(budget.effectiveContextWindow()).isEqualTo(64);
        assertThat(budget.autoCompactThreshold()).isEqualTo(10);
        assertThat(budget.estimatedContextTokens()).isGreaterThan(10);
    }

    @Test
    void buildsContextFromCurrentBranchInChronologicalOrder() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.appendMessage(userMessage("msg-user", "hello"));
        session.appendMessage(assistantMessage("msg-assistant", "hi"));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            true
        ));

        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::id)
            .containsExactly("msg-user", "msg-assistant");
        assertThat(assembly.branchEntryIds()).containsExactly("entry-msg-user", "entry-msg-assistant");
        assertThat(assembly.snapshot().systemPrompt().content()).isEqualTo("system");
    }

    @Test
    void restoresLatestStateEntries() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new ModelChangeEntry("entry-model", "", new ModelSelection("openai", "gpt-test", ThinkingLevel.HIGH), "test", NOW));
        session.append(new ThinkingChangeEntry("entry-thinking", "entry-model", ThinkingLevel.HIGH, "test", NOW));
        session.append(new ModeChangeEntry("entry-mode", "entry-thinking", AgentMode.PLAN, "test", NOW));
        session.append(new PermissionModeChangeEntry("entry-permission", "entry-mode", PermissionMode.PLAN, "test", NOW));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            false
        ));

        assertThat(assembly.snapshot().model().modelId()).isEqualTo("gpt-test");
        assertThat(assembly.snapshot().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(assembly.snapshot().mode()).isEqualTo(AgentMode.PLAN);
        assertThat(assembly.snapshot().permissionMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(assembly.snapshot().systemPrompt()).isNull();
    }

    @Test
    void restoresStateEntriesAfterCompaction() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new ModelChangeEntry("entry-model", "", new ModelSelection("openai", "gpt-test", ThinkingLevel.HIGH), "test", NOW));
        session.append(new ThinkingChangeEntry("entry-thinking", "entry-model", ThinkingLevel.HIGH, "test", NOW));
        session.append(new ModeChangeEntry("entry-mode", "entry-thinking", AgentMode.PLAN, "test", NOW));
        session.append(new PermissionModeChangeEntry("entry-permission", "entry-mode", PermissionMode.PLAN, "test", NOW));
        session.append(new MessageEntry("entry-kept", "entry-permission", userMessage("msg-kept", "kept"), NOW));
        session.append(new CompactionEntry("entry-compact", "entry-kept", "summary text", "entry-kept", 100, 20, CompactionKind.SESSION, NOW));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            false
        ));

        assertThat(assembly.snapshot().model().modelId()).isEqualTo("gpt-test");
        assertThat(assembly.snapshot().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(assembly.snapshot().mode()).isEqualTo(AgentMode.PLAN);
        assertThat(assembly.snapshot().permissionMode()).isEqualTo(PermissionMode.PLAN);
    }

    @Test
    void appliesLatestCompactionSummaryAndKeepsMessagesFromFirstKeptEntry() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "old"), NOW));
        session.append(new MessageEntry("entry-kept", "entry-old", userMessage("msg-kept", "kept"), NOW));
        session.append(new CompactionEntry("entry-compact", "entry-kept", "summary text", "entry-kept", 100, 20, CompactionKind.SESSION, NOW));
        session.append(new MessageEntry("entry-after", "entry-compact", assistantMessage("msg-after", "after"), NOW));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            true
        ));

        assertThat(assembly.appliedCompactionEntryIds()).containsExactly("entry-compact");
        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::kind)
            .containsExactly(MessageKind.SUMMARY, MessageKind.TEXT, MessageKind.TEXT);
        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-entry-compact", "msg-kept", "msg-after");
    }

    @Test
    void appliesOnlyLatestCompactionSummary() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new MessageEntry("entry-old", "", userMessage("msg-old", "old"), NOW));
        session.append(new MessageEntry("entry-kept-1", "entry-old", userMessage("msg-kept-1", "kept 1"), NOW));
        session.append(new CompactionEntry("entry-compact-1", "entry-kept-1", "summary one", "entry-kept-1", 100, 20, CompactionKind.SESSION, NOW));
        session.append(new MessageEntry("entry-middle", "entry-compact-1", assistantMessage("msg-middle", "middle"), NOW));
        session.append(new MessageEntry("entry-kept-2", "entry-middle", userMessage("msg-kept-2", "kept 2"), NOW));
        session.append(new CompactionEntry("entry-compact-2", "entry-kept-2", "summary two", "entry-kept-2", 80, 18, CompactionKind.SESSION, NOW));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            true
        ));

        assertThat(assembly.appliedCompactionEntryIds()).containsExactly("entry-compact-2");
        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-entry-compact-2", "msg-kept-2");
        assertThat(assembly.snapshot().messages().getFirst().content().getFirst().text()).isEqualTo("summary two");
    }

    @Test
    void projectsBranchSummaryAndCustomMessageEntries() {
        AgentCoreTestFixtures.InMemorySessionEngine session = new AgentCoreTestFixtures.InMemorySessionEngine();
        session.openOrCreate("session-1");
        session.append(new BranchSummaryEntry("entry-branch-summary", "", "left branch summary", NOW));
        session.append(new CustomMessageEntry("entry-custom", "entry-branch-summary", "local instruction", NOW));

        DefaultContextAssembler assembler = new DefaultContextAssembler(
            session,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of(session.leafId()),
            Path.of("."),
            true
        ));

        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::id)
            .containsExactly("branch-summary-entry-branch-summary", "custom-message-entry-custom");
        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::kind)
            .containsExactly(MessageKind.SUMMARY, MessageKind.TEXT);
        assertThat(assembly.snapshot().messages())
            .extracting(AgentMessage::role)
            .containsExactly(MessageRole.SYSTEM_LOCAL, MessageRole.SYSTEM_LOCAL);
        assertThat(assembly.snapshot().messages())
            .extracting(message -> message.content().getFirst().text())
            .containsExactly("left branch summary", "local instruction");
    }
}
