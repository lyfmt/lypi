package cn.lypi.agent;

import static cn.lypi.agent.AgentCoreTestFixtures.fixedResourceRuntime;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class DefaultContextAssemblerTest {
    @Test
    void usesConfiguredBudgetThresholds() {
        ContextBudgetEstimator estimator = new ContextBudgetEstimator(64, 10, 8, 4);

        ContextBudget budget = estimator.estimate(List.of(userMessage(
            "msg-user",
            "01234567890123456789012345678901234567890123"
        )));

        assertThat(budget.effectiveContextWindow()).isEqualTo(64);
        assertThat(budget.autoCompactThreshold()).isEqualTo(10);
        assertThat(budget.estimatedContextTokens()).isGreaterThan(10);
    }

    @Test
    void buildsContextFromSessionManagerContextAndResourceRuntime() {
        AgentMessage userMessage = userMessage("msg-user", "hello");
        String systemPrompt = "system prompt with enough characters";
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage),
            List.of("entry-user"),
            List.of("entry-compact"),
            new ModelSelection("openai", "gpt-test", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            fixedResourceRuntime(systemPrompt),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true
        ));

        assertThat(sessionManager.requestedLeafId).isEqualTo("entry-user");
        assertThat(assembly.snapshot().messages()).containsExactly(userMessage);
        assertThat(assembly.snapshot().systemPrompt().content()).isEqualTo(systemPrompt);
        assertThat(assembly.snapshot().budget().estimatedContextTokens())
            .isEqualTo(estimateTokens(assembly.snapshot().systemPrompt(), assembly.snapshot().messages()));
        assertThat(assembly.snapshot().model().modelId()).isEqualTo("gpt-test");
        assertThat(assembly.snapshot().thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(assembly.snapshot().mode()).isEqualTo(AgentMode.PLAN);
        assertThat(assembly.snapshot().permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
        assertThat(assembly.branchEntryIds()).containsExactly("entry-user");
        assertThat(assembly.appliedCompactionEntryIds()).containsExactly("entry-compact");
        assertThat(assembly.budgetExceeded()).isFalse();
    }

    @Test
    void usesCurrentLeafWhenRequestLeafIsAbsent() {
        AgentMessage userMessage = userMessage("msg-user", "hello");
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage),
            List.of("entry-current"),
            List.of(),
            new ModelSelection("default", "default", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        assembler.build(new ContextBuildRequest("session-1", Optional.empty(), Path.of("."), false));

        assertThat(sessionManager.requestedLeafId).isEqualTo("entry-current");
    }

    private static final class StubSessionManager extends AgentCoreTestFixtures.InMemorySessionManager {
        private final SessionContext context;
        private String requestedLeafId;

        private StubSessionManager(SessionContext context) {
            this.context = context;
        }

        @Override
        public SessionHandle openOrCreate(String sessionId) {
            super.openOrCreate(sessionId);
            return new SessionHandle(sessionId, Path.of("test-session.jsonl"), "entry-current", java.util.Map.of());
        }

        @Override
        public SessionContext context(String leafId) {
            requestedLeafId = leafId;
            return context;
        }

        @Override
        public List<SessionEntry> branch(String leafId) {
            return List.of();
        }
    }

    private static int estimateTokens(SystemPrompt systemPrompt, List<AgentMessage> messages) {
        int systemTokens = systemPrompt == null ? 0 : estimateText(systemPrompt.content());
        int messageTokens = messages.stream()
            .flatMap(message -> message.content().stream())
            .mapToInt(DefaultContextAssemblerTest::estimateBlock)
            .sum();
        return systemTokens + messageTokens;
    }

    private static int estimateBlock(ContentBlock block) {
        return estimateText(block.text());
    }

    private static int estimateText(String text) {
        String safeText = text == null ? "" : text;
        return Math.max(1, safeText.length() / 4);
    }
}
