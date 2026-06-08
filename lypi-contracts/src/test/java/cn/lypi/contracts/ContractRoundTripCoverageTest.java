package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.mcp.McpServerConfig;
import cn.lypi.contracts.mcp.McpTransport;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.PromptTemplate;
import cn.lypi.contracts.prompt.PromptTemplateSource;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.resource.ContextFile;
import cn.lypi.contracts.resource.MemorySource;
import cn.lypi.contracts.resource.ResourceDiagnostic;
import cn.lypi.contracts.resource.ResourceDiagnosticLevel;
import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionOptionPolicy;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.skill.SkillSource;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.contracts.tui.TuiEphemeralState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractRoundTripCoverageTest {
    private static final Instant NOW = Instant.parse("2026-06-01T12:00:00Z");
    private static final Path REPO_ROOT = Path.of("/tmp/lypi-contracts-round-trip");

    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    @Test
    void eventContextToolSecurityAndResourceRecordsRoundTripWithJackson() throws Exception {
        EventEnvelope retryEnvelope = new EventEnvelope(
            "evt_retry",
            "ses_01",
            1,
            new RetryStartEvent("ses_01", 2, "rate limit", NOW)
        );
        EventEnvelope compactEnvelope = new EventEnvelope(
            "evt_compact",
            "ses_01",
            2,
            new CompactEndEvent("ses_01", "entry_compact", NOW)
        );
        ContextSnapshot context = new ContextSnapshot(
            new SystemPrompt("system", List.of("project"), "sha256:system"),
            List.of(textMessage("msg_01", "hello")),
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.PLAN,
            new ContextBudget(100, 200, 150, 40, 30, 10L, 5L, BigDecimal.valueOf(0.42))
        );
        ToolUseRequest request = new ToolUseRequest(
            "toolu_01",
            "bash",
            Map.of("command", "git status"),
            "msg_01"
        );
        ToolUseContext toolContext = new ToolUseContext(
            "ses_01",
            "msg_01",
            Path.of("/repo"),
            Map.of("turnId", "turn_01")
        );
        ToolRegistrySnapshot registry = new ToolRegistrySnapshot(List.of(
            new ToolDescriptor("bash", List.of("shell"), false, true)
        ));
        BashRiskAnalysis risk = new BashRiskAnalysis(
            "git status",
            List.of("git status"),
            List.of(REPO_ROOT.resolve("target/out.txt")),
            BashRiskLevel.LOW,
            List.of("read-only git command"),
            true
        );
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "needs approval",
            Optional.of(permissionUpdate(PermissionRuleSource.SESSION)),
            Map.of("riskLevel", "LOW")
        );
        ResourceSnapshot resources = new ResourceSnapshot(
            List.of(new ContextFile(REPO_ROOT.resolve("AGENTS.md"), "rules", "sha256:agents")),
            List.of(new MemorySource(REPO_ROOT.resolve(".lypi/memory.md"), "sha256:memory")),
            new SkillIndex(List.of(new SkillDescriptor(
                "demo",
                "demo skill",
                SkillSource.PROJECT,
                REPO_ROOT.resolve(".codex/skills/demo/SKILL.md"),
                List.of("*.java"),
                List.of("bash"),
                "sha256:skill"
            )), List.of()),
            List.of(new PromptTemplate(
                "plan",
                "plan prompt",
                PromptTemplateSource.PROJECT,
                List.of(),
                "Plan: {{input}}",
                "sha256:prompt"
            )),
            List.of(new McpServerConfig(
                "filesystem",
                McpTransport.STDIO,
                List.of("node", "server.js"),
                Map.of("ROOT", "/repo"),
                Duration.ofSeconds(5),
                Duration.ofSeconds(30)
            )),
            List.of(new ResourceDiagnostic(
                ResourceDiagnosticLevel.WARNING,
                "ignored malformed frontmatter",
                Optional.of(REPO_ROOT.resolve("bad.md"))
            ))
        );

        EventEnvelope restoredRetry = roundTrip(retryEnvelope, EventEnvelope.class);
        EventEnvelope restoredCompact = roundTrip(compactEnvelope, EventEnvelope.class);
        ContextSnapshot restoredContext = roundTrip(context, ContextSnapshot.class);
        ToolUseRequest restoredRequest = roundTrip(request, ToolUseRequest.class);
        ToolUseContext restoredToolContext = roundTrip(toolContext, ToolUseContext.class);
        ToolRegistrySnapshot restoredRegistry = roundTrip(registry, ToolRegistrySnapshot.class);
        BashRiskAnalysis restoredRisk = roundTrip(risk, BashRiskAnalysis.class);
        PermissionDecision restoredDecision = roundTrip(decision, PermissionDecision.class);
        ResourceSnapshot restoredResources = roundTrip(resources, ResourceSnapshot.class);

        assertAll(
            () -> assertInstanceOf(RetryStartEvent.class, restoredRetry.event()),
            () -> assertEquals(2, ((RetryStartEvent) restoredRetry.event()).attempt()),
            () -> assertInstanceOf(CompactEndEvent.class, restoredCompact.event()),
            () -> assertEquals("entry_compact", ((CompactEndEvent) restoredCompact.event()).compactionEntryId()),
            () -> assertEquals("gpt-5.4", restoredContext.model().modelId()),
            () -> assertEquals("hello", restoredContext.messages().getFirst().content().getFirst().text()),
            () -> assertEquals("git status", restoredRequest.input().get("command")),
            () -> assertEquals(Path.of("/repo"), restoredToolContext.cwd()),
            () -> assertEquals("bash", restoredRegistry.tools().getFirst().name()),
            () -> assertEquals(BashRiskLevel.LOW, restoredRisk.riskLevel()),
            () -> assertEquals(PermissionBehavior.ASK, restoredDecision.behavior()),
            () -> assertEquals(PermissionRuleSource.SESSION, restoredDecision.suggestedUpdate().orElseThrow().targetSource()),
            () -> assertTrue(restoredResources.agentFiles().getFirst().path().endsWith(Path.of("AGENTS.md"))),
            () -> assertEquals("filesystem", restoredResources.mcpServers().getFirst().name())
        );
    }

    @Test
    void permissionOptionPolicyKeepsAllowDenyAndAbortOptionsConsistent() {
        PermissionOptionPolicy.Options lowRisk = PermissionOptionPolicy.fromDecision(new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "needs approval",
            Optional.empty(),
            Map.of("riskLevel", "LOW")
        ));
        PermissionOptionPolicy.Options destructive = PermissionOptionPolicy.fromDecision(new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "dangerous command",
            Optional.empty(),
            Map.of("riskLevel", "DESTRUCTIVE")
        ));

        assertAll(
            () -> assertEquals("allow_once", lowRisk.defaultOptionId()),
            () -> assertEquals("deny", destructive.defaultOptionId()),
            () -> assertEquals("cancel", lowRisk.cancelOptionId()),
            () -> assertEquals("cancel", destructive.cancelOptionId()),
            () -> assertOption(lowRisk, "allow_once", PermissionOptionKind.ALLOW_ONCE),
            () -> assertOption(lowRisk, "deny", PermissionOptionKind.DENY),
            () -> assertOption(lowRisk, "cancel", PermissionOptionKind.CANCEL),
            () -> assertOption(destructive, destructive.defaultOptionId(), PermissionOptionKind.DENY),
            () -> assertOption(destructive, destructive.cancelOptionId(), PermissionOptionKind.CANCEL)
        );
    }

    @Test
    void tuiEphemeralStateIsExcludedFromSessionPersistenceContract() throws Exception {
        TuiEphemeralState state = new TuiEphemeralState(
            Set.of("block_01"),
            Set.of("toolu_01"),
            Optional.of("files"),
            42,
            Map.of("pane", "diff")
        );
        SessionEntry entry = new MessageEntry("entry_01", null, textMessage("msg_01", "persisted"), NOW);

        String entryJson = mapper.writeValueAsString(entry);
        SessionEntry restored = mapper.readValue(entryJson, SessionEntry.class);

        assertFalse(SessionEntry.class.isAssignableFrom(TuiEphemeralState.class));
        assertInstanceOf(MessageEntry.class, restored);
        assertTrue(entryJson.contains("\"type\":\"message\""));
        assertFalse(entryJson.contains("collapsedBlockIds"));
        assertEquals(Set.of("block_01"), state.collapsedBlockIds());
    }

    private <T> T roundTrip(T value, Class<T> type) throws Exception {
        return mapper.readValue(mapper.writeValueAsString(value), type);
    }

    private static void assertOption(
        PermissionOptionPolicy.Options options,
        String optionId,
        PermissionOptionKind kind
    ) {
        Optional<PermissionOption> option = options.options().stream()
            .filter(candidate -> candidate.optionId().equals(optionId))
            .findFirst();
        assertTrue(option.isPresent(), () -> "missing option " + optionId);
        assertEquals(kind, option.orElseThrow().kind());
    }

    private static PermissionUpdate permissionUpdate(PermissionRuleSource source) {
        return new PermissionUpdate(
            source,
            new PermissionRule(
                source,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "git status *"),
                "allow status"
            )
        );
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
