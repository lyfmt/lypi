package cn.lypi.agent;

import static cn.lypi.agent.AgentCoreTestFixtures.fixedResourceRuntime;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.model.ApiStyle;
import cn.lypi.contracts.model.CostProfile;
import cn.lypi.contracts.model.ModelCatalogPort;
import cn.lypi.contracts.model.ModelDescriptor;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.skill.SkillMention;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    void derivesAutoCompactThresholdFromCatalogContextWindow() {
        ContextBudgetEstimator estimator = new ContextBudgetEstimator(catalogWith(descriptor("fixture", "small", 64_000)));

        ContextBudget budget = estimator.estimate(
            null,
            List.of(userMessage("msg-user", "hello")),
            new ModelSelection("fixture", "small", ThinkingLevel.MEDIUM)
        );

        assertThat(budget.effectiveContextWindow()).isEqualTo(64_000);
        assertThat(budget.autoCompactThreshold()).isEqualTo(51_200);
    }

    @Test
    void fallsBackTo256kWindowWhenCatalogMissesCurrentModel() {
        ContextBudgetEstimator estimator = new ContextBudgetEstimator(selection -> Optional.empty());

        ContextBudget budget = estimator.estimate(
            null,
            List.of(userMessage("msg-user", "hello")),
            new ModelSelection("fixture", "missing", ThinkingLevel.MEDIUM)
        );

        assertThat(budget.effectiveContextWindow()).isEqualTo(256_000);
        assertThat(budget.autoCompactThreshold()).isEqualTo(204_800);
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
    void buildsBudgetFromCurrentSessionModelContextWindow() {
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage("msg-user", "hello")),
            List.of("entry-user"),
            List.of(),
            new ModelSelection("fixture", "configured-model", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator(catalogWith(descriptor("fixture", "configured-model", 80_000)))
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true
        ));

        assertThat(assembly.snapshot().budget().effectiveContextWindow()).isEqualTo(80_000);
        assertThat(assembly.snapshot().budget().autoCompactThreshold()).isEqualTo(64_000);
    }

    @Test
    void preservesCanonicalPermissionRuntimeStateFromSessionContext() {
        PermissionRuntimeState runtimeState = customPermissionRuntimeState();
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage("msg-user", "hello")),
            List.of("entry-user"),
            List.of(),
            new ModelSelection("fixture", "configured-model", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            runtimeState
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            fixedResourceRuntime("system"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true
        ));

        assertThat(assembly.snapshot().permissionRuntimeState()).isEqualTo(runtimeState);
    }

    @Test
    void passesCanonicalPermissionRuntimeStateIntoSystemPromptBuilder() {
        PermissionRuntimeState runtimeState = customPermissionRuntimeState();
        RecordingResourceRuntime resourceRuntime = new RecordingResourceRuntime();
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage("msg-user", "hello")),
            List.of("entry-user"),
            List.of(),
            new ModelSelection("fixture", "configured-model", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            runtimeState
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            resourceRuntime,
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true
        ));

        assertThat(resourceRuntime.promptPermissionRuntimeState).isEqualTo(runtimeState);
        assertThat(assembly.snapshot().systemPrompt().content()).contains("runtime=UNLESS_TRUSTED");
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

    @Test
    void injectsExplicitSkillBodiesIntoTurnLocalSystemPrompt() throws Exception {
        Path skillFile = Files.createTempFile("skill", ".md");
        Files.writeString(skillFile, """
            ---
            name: doc
            description: Document workflow
            ---
            Use python-docx.
            """);
        StubSessionManager sessionManager = new StubSessionManager(new SessionContext(
            List.of(userMessage("msg-user", "use $doc")),
            List.of("entry-user"),
            List.of(),
            new ModelSelection("default", "default", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.DEFAULT_EXECUTE
        ));
        DefaultContextAssembler assembler = new DefaultContextAssembler(
            sessionManager,
            fixedResourceRuntime("skill index only"),
            new ContextBudgetEstimator()
        );

        ContextAssembly assembly = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true,
            List.of(new SkillMention("doc", skillFile))
        ));

        assertThat(assembly.snapshot().systemPrompt().content())
            .contains("skill index only")
            .contains("<skill>")
            .contains("<name>doc</name>")
            .contains("<path>" + skillFile + "</path>")
            .contains("Use python-docx.");

        ContextAssembly nextTurn = assembler.build(new ContextBuildRequest(
            "session-1",
            Optional.of("entry-user"),
            Path.of("."),
            true
        ));
        assertThat(nextTurn.snapshot().systemPrompt().content()).doesNotContain("<skill>");
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

    private static final class RecordingResourceRuntime implements ResourceRuntimePort {
        private final ResourceSnapshot resources = AgentCoreTestFixtures.emptyResources();
        private PermissionRuntimeState promptPermissionRuntimeState;

        @Override
        public ResourceSnapshot load(Path cwd) {
            return resources;
        }

        @Override
        public SystemPrompt buildSystemPrompt(ResourceSnapshot resources, PermissionRuntimeState permissionRuntimeState) {
            promptPermissionRuntimeState = permissionRuntimeState;
            return new SystemPrompt(
                "runtime=" + permissionRuntimeState.approvalPolicy().mode(),
                List.of("test"),
                "hash"
            );
        }

        @Override
        public SystemPrompt buildSystemPrompt(ResourceSnapshot resources) {
            throw new AssertionError("canonical permission runtime state overload must be used");
        }
    }

    private static ModelCatalogPort catalogWith(ModelDescriptor descriptor) {
        return selection -> descriptor.provider().equals(selection.provider())
            && descriptor.modelId().equals(selection.modelId())
            ? Optional.of(descriptor)
            : Optional.empty();
    }

    private static ModelDescriptor descriptor(String provider, String modelId, int contextWindow) {
        return new ModelDescriptor(
            provider,
            modelId,
            URI.create("https://example.test/v1"),
            ApiStyle.OPENAI_COMPATIBLE,
            contextWindow,
            8_192,
            true,
            false,
            new CostProfile(BigDecimal.ZERO, BigDecimal.ZERO, "USD"),
            Map.of()
        );
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

    private static PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            cn.lypi.contracts.security.PermissionProfiles.workspace(),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.DEFAULT_EXECUTE
        );
    }
}
