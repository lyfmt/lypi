package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ErrorContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.LyPiException;
import cn.lypi.contracts.error.ToolValidationException;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.MessageBlockSnapshot;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.MessageStartEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.PermissionResponseEvent;
import cn.lypi.contracts.event.SessionStateEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.AssistantStreamEvent;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ProviderRetryNotice;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.runtime.ExecutionMetadata;
import cn.lypi.contracts.runtime.ExecutionRequest;
import cn.lypi.contracts.runtime.ExecutionResult;
import cn.lypi.contracts.runtime.NetworkMode;
import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.AgentLifecycleEntry;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import cn.lypi.contracts.session.SessionInfoEntry;
import cn.lypi.contracts.subagent.AgentRunStatus;
import cn.lypi.contracts.subagent.AgentView;
import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import cn.lypi.contracts.subagent.SubagentResultRef;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import cn.lypi.contracts.tui.PermissionPromptView;
import cn.lypi.contracts.tui.SessionFileView;
import cn.lypi.contracts.tui.StatusBarState;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ContractSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    @Test
    void modeContractsExposeOnlySeparatedStageAndPermissionValues() {
        assertEquals(List.of(AgentMode.PLAN, AgentMode.EXECUTE), List.of(AgentMode.values()));
        assertEquals(
            List.of(PermissionMode.DEFAULT_EXECUTE, PermissionMode.ACCEPT_EDITS, PermissionMode.BYPASS),
            List.of(PermissionMode.values())
        );
    }

    @Test
    void sandboxPermissionsParseToolInputValues() {
        assertEquals(SandboxPermissions.USE_DEFAULT, SandboxPermissions.fromToolValue(null));
        assertEquals(SandboxPermissions.USE_DEFAULT, SandboxPermissions.fromToolValue("useDefault"));
        assertEquals(SandboxPermissions.REQUIRE_ESCALATED, SandboxPermissions.fromToolValue("requireEscalated"));
    }

    @Test
    void executionRequestDefaultsToDefaultSandboxPermissions() {
        ExecutionRequest request = new ExecutionRequest(
            List.of("bash", "-lc", "true"),
            Path.of("."),
            Map.of(),
            Duration.ofSeconds(1),
            null
        );

        assertEquals(SandboxPermissions.USE_DEFAULT, request.sandboxPermissions());
        assertEquals(Optional.empty(), request.justification());
    }

    @Test
    void sandboxRuntimePolicyRoundTripKeepsNetworkModeWithoutDomainFields() throws Exception {
        SandboxRuntimePolicy policy = new SandboxRuntimePolicy(
            List.of(Path.of("/usr")),
            List.of(Path.of("/secret")),
            List.of(Path.of("/workspace")),
            List.of(),
            NetworkMode.DISABLED,
            false,
            false
        );

        String json = mapper.writeValueAsString(policy);
        SandboxRuntimePolicy restored = mapper.readValue(json, SandboxRuntimePolicy.class);

        assertTrue(json.contains("\"networkMode\":\"DISABLED\""));
        assertTrue(!json.contains("allowedDomains"));
        assertTrue(!json.contains("deniedDomains"));
        assertEquals(NetworkMode.DISABLED, restored.networkMode());
        assertEquals(false, restored.failIfUnavailable());
        assertEquals(false, restored.autoAllowBashIfSandboxed());
    }

    @Test
    void executionResultRoundTripKeepsSandboxMetadata() throws Exception {
        ExecutionResult result = new ExecutionResult(
            0,
            "out",
            "err",
            false,
            Optional.empty(),
            new ExecutionMetadata(true, "bubblewrap", Optional.empty())
        );

        String json = mapper.writeValueAsString(result);
        ExecutionResult restored = mapper.readValue(json, ExecutionResult.class);

        assertTrue(json.contains("\"sandboxed\":true"));
        assertTrue(json.contains("\"executorName\":\"bubblewrap\""));
        assertEquals(true, restored.metadata().sandboxed());
        assertEquals("bubblewrap", restored.metadata().executorName());
    }

    @Test
    void sessionEntryRoundTripUsesTypeDiscriminator() throws Exception {
        SessionEntry entry = new CustomMessageEntry(
            "ent_01",
            "ent_00",
            "skill activated",
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(entry);
        SessionEntry restored = mapper.readValue(json, SessionEntry.class);

        assertTrue(json.contains("\"type\":\"custom_message\""));
        assertInstanceOf(CustomMessageEntry.class, restored);
        assertEquals("ent_01", restored.id());
    }

    @Test
    void branchSummaryEntryRoundTripPreservesSourceLeaf() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        SessionEntry entry = new BranchSummaryEntry("entry-branch", "entry-target", "entry-old-leaf", "branch summary", now);

        String json = mapper.writeValueAsString(entry);
        SessionEntry restored = mapper.readValue(json, SessionEntry.class);

        assertTrue(json.contains("\"type\":\"branch_summary\""));
        assertTrue(json.contains("\"fromId\":\"entry-old-leaf\""));
        assertInstanceOf(BranchSummaryEntry.class, restored);
        BranchSummaryEntry branchSummary = (BranchSummaryEntry) restored;
        assertEquals("entry-old-leaf", branchSummary.fromId());
        assertEquals(entry, branchSummary);
    }

    @Test
    void legacyBranchSummaryEntryWithoutSourceLeafStillDeserializes() throws Exception {
        String json = """
            {
              "type": "branch_summary",
              "id": "entry-branch",
              "parentId": "entry-target",
              "summary": "legacy branch summary",
              "timestamp": "2026-06-01T12:00:00Z"
            }
            """;

        SessionEntry restored = mapper.readValue(json, SessionEntry.class);

        assertInstanceOf(BranchSummaryEntry.class, restored);
        BranchSummaryEntry branchSummary = (BranchSummaryEntry) restored;
        assertEquals("entry-branch", branchSummary.id());
        assertEquals("entry-target", branchSummary.parentId());
        assertEquals(null, branchSummary.fromId());
        assertEquals("legacy branch summary", branchSummary.summary());
    }

    @Test
    void sessionEntriesRoundTripOnlyForConversationPathFacts() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        List<SessionEntry> entries = List.of(
            new CustomEntry("entry-custom", null, "demo.extension", Map.of("enabled", true), now),
            new BranchSummaryEntry("entry-branch", "entry-custom", "entry-old-leaf", "branch summary", now),
            new CustomMessageEntry("entry-custom-message", "entry-branch", "hello", now),
            new CompactionEntry(
                "entry-compact",
                "entry-custom-message",
                "summary",
                "entry-custom-message",
                100,
                20,
                CompactionKind.SESSION,
                now
            ),
            new SessionInfoEntry("entry-info", "entry-compact", Map.of("name", "demo"), now)
        );

        for (SessionEntry entry : entries) {
            String json = mapper.writeValueAsString(entry);
            SessionEntry restored = mapper.readValue(json, SessionEntry.class);
            assertEquals(entry, restored);
        }
    }

    @Test
    void sessionHeaderRoundTripKeepsSubagentRelationshipFields() throws Exception {
        SessionHeader header = new SessionHeader(
            "session",
            1,
            "ses_child",
            Path.of("/tmp/project"),
            Optional.of("ses_parent"),
            Optional.of("entry_spawn"),
            2,
            Optional.of("reviewer"),
            Optional.of("code-review"),
            Instant.parse("2026-06-09T00:00:00Z")
        );

        String json = mapper.writeValueAsString(header);
        SessionHeader restored = mapper.readValue(json, SessionHeader.class);

        assertEquals(header, restored);
        assertTrue(json.contains("\"parentSessionId\":\"ses_parent\""));
        assertTrue(json.contains("\"parentSpawnEntryId\":\"entry_spawn\""));
        assertTrue(json.contains("\"depth\":2"));
    }

    @Test
    void sessionStateEventRoundTripKeepsRuntimeProjectionFields() throws Exception {
        AgentEvent event = new SessionStateEvent(
            "ses_1",
            "entry_1",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.PLAN,
            PermissionMode.DEFAULT_EXECUTE,
            Instant.parse("2026-06-11T00:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"session_state\""));
        SessionStateEvent state = assertInstanceOf(SessionStateEvent.class, restored);
        assertEquals("entry_1", state.leafId());
        assertEquals(new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH), state.model());
        assertEquals(ThinkingLevel.HIGH, state.thinkingLevel());
        assertEquals(AgentMode.PLAN, state.agentMode());
        assertEquals(PermissionMode.DEFAULT_EXECUTE, state.permissionMode());
    }

    @Test
    void legacySessionHeaderDefaultsSubagentRelationshipFields() throws Exception {
        String json = """
            {
              "type": "session",
              "version": 1,
              "id": "ses_main",
              "cwd": "/tmp/project",
              "parentSessionId": null,
              "timestamp": "2026-06-09T00:00:00Z"
            }
            """;

        SessionHeader restored = mapper.readValue(json, SessionHeader.class);

        assertEquals(Optional.empty(), restored.parentSpawnEntryId());
        assertEquals(0, restored.depth());
        assertEquals(Optional.empty(), restored.agentName());
        assertEquals(Optional.empty(), restored.agentRole());
    }

    @Test
    void subagentContractsRoundTripKeepSessionRelationsAndMailboxStatus() throws Exception {
        Instant now = Instant.parse("2026-06-09T00:00:00Z");
        SessionEntry entry = new AgentLifecycleEntry(
            "entry_spawn",
            "entry_parent",
            "agent_01",
            "ses_child",
            "ses_parent",
            "spawned",
            Map.of("agentName", "reviewer"),
            now
        );

        String entryJson = mapper.writeValueAsString(entry);
        SessionEntry restoredEntry = mapper.readValue(entryJson, SessionEntry.class);

        assertTrue(entryJson.contains("\"type\":\"agent_lifecycle\""));
        assertInstanceOf(AgentLifecycleEntry.class, restoredEntry);
        assertEquals(entry, restoredEntry);

        MailboxMessage message = new MailboxMessage(
            "mail_01",
            "agent_01",
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "完成摘要",
            new SubagentResultRef("ses_child", "entry_final", Optional.empty()),
            MailboxStatus.PENDING,
            now,
            now
        );

        String messageJson = mapper.writeValueAsString(message);
        MailboxMessage restoredMessage = mapper.readValue(messageJson, MailboxMessage.class);

        assertEquals(message, restoredMessage);
        assertTrue(messageJson.contains("\"status\":\"PENDING\""));
    }

    @Test
    void agentViewRoundTripKeepsManagementFields() throws Exception {
        AgentView view = new AgentView(
            "agent_01",
            "Scout [explorer]",
            "ses_parent",
            "ses_child",
            "entry_spawn",
            AgentRunStatus.RUNNING,
            Optional.of(MailboxStatus.PENDING),
            Optional.of("完成摘要"),
            Optional.of("entry_final"),
            Optional.of("Scout"),
            Optional.of("explorer")
        );

        String json = mapper.writeValueAsString(view);
        AgentView restored = mapper.readValue(json, AgentView.class);

        assertEquals(view, restored);
        assertTrue(json.contains("\"status\":\"RUNNING\""));
        assertTrue(json.contains("\"mailboxStatus\":\"PENDING\""));
    }

    @Test
    void agentEventRoundTripUsesTypeDiscriminatorInsideEnvelope() throws Exception {
        EventEnvelope envelope = new EventEnvelope(
            "evt_01",
            "ses_01",
            7,
            new TurnStartEvent("ses_01", "turn_01", Instant.parse("2026-06-01T12:00:00Z"))
        );

        String json = mapper.writeValueAsString(envelope);
        EventEnvelope restored = mapper.readValue(json, EventEnvelope.class);

        assertTrue(json.contains("\"type\":\"turn_start\""));
        assertInstanceOf(TurnStartEvent.class, restored.event());
        assertEquals(7, restored.sequence());
    }

    @Test
    void providerRetryNoticeRoundTripUsesTypeDiscriminator() throws Exception {
        AssistantStreamEvent event = new ProviderRetryNotice(
            "openai",
            2,
            3,
            java.time.Duration.ofMillis(1_000),
            "rate_limit",
            "provider.rate_limit",
            "Provider rate limited the request."
        );

        String json = mapper.writeValueAsString(event);
        AssistantStreamEvent restored = mapper.readValue(json, AssistantStreamEvent.class);

        assertTrue(json.contains("\"type\":\"provider_retry\""));
        ProviderRetryNotice notice = assertInstanceOf(ProviderRetryNotice.class, restored);
        assertEquals("openai", notice.provider());
        assertEquals(2, notice.attempt());
        assertEquals(java.time.Duration.ofMillis(1_000), notice.delay());
        assertEquals("provider.rate_limit", notice.retryableErrorId());
    }

    @Test
    void toolProgressEventRoundTripKeepsStructuredProgress() throws Exception {
        AgentEvent event = new ToolProgressEvent(
            "ses_01",
            "toolu_01",
            ToolProgress.counter("扫描文件", 3, 10),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"tool_progress\""));
        assertTrue(json.contains("\"kind\":\"COUNTER\""));
        ToolProgressEvent progress = assertInstanceOf(ToolProgressEvent.class, restored);
        assertEquals(ToolProgressKind.COUNTER, progress.progress().kind());
        assertEquals("扫描文件", progress.progress().title());
        assertEquals(3L, progress.progress().current());
        assertEquals(10L, progress.progress().total());
    }

    @Test
    void semanticMessageEventsRoundTripKeepsRoleBlocksAndMetadata() throws Exception {
        Instant timestamp = Instant.parse("2026-06-01T12:00:00Z");
        AgentEvent start = new MessageStartEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            Map.of("phase", "stream"),
            timestamp
        );
        AgentEvent delta = new MessageDeltaEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "block_01",
            ContentBlockKind.TEXT,
            "hello",
            true,
            Map.of("index", 0),
            timestamp
        );
        AgentEvent end = new MessageEndEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(new MessageBlockSnapshot("block_01", ContentBlockKind.TEXT, "hello", Map.of("index", 0))),
            Optional.of(new TokenUsage(10, 5, 0, 0)),
            Optional.of("end_turn"),
            Map.of("final", true),
            timestamp
        );

        MessageStartEvent restoredStart = assertInstanceOf(
            MessageStartEvent.class,
            mapper.readValue(mapper.writeValueAsString(start), AgentEvent.class)
        );
        assertEquals(MessageRole.ASSISTANT, restoredStart.role());
        assertEquals(MessageKind.TEXT, restoredStart.kind());
        assertEquals("stream", restoredStart.metadata().get("phase"));

        MessageDeltaEvent restoredDelta = assertInstanceOf(
            MessageDeltaEvent.class,
            mapper.readValue(mapper.writeValueAsString(delta), AgentEvent.class)
        );
        assertEquals("block_01", restoredDelta.blockId());
        assertEquals(ContentBlockKind.TEXT, restoredDelta.blockKind());
        assertEquals("hello", restoredDelta.delta());
        assertTrue(restoredDelta.isFinal());

        MessageEndEvent restoredEnd = assertInstanceOf(
            MessageEndEvent.class,
            mapper.readValue(mapper.writeValueAsString(end), AgentEvent.class)
        );
        assertEquals("block_01", restoredEnd.blocks().getFirst().blockId());
        assertEquals("hello", restoredEnd.blocks().getFirst().text());
        assertEquals(10, restoredEnd.usage().orElseThrow().inputTokens());
        assertEquals("end_turn", restoredEnd.stopReason().orElseThrow());
        assertEquals(true, restoredEnd.metadata().get("final"));
    }

    @Test
    void toolLifecycleContractsRoundTripKeepsStructuredSummaryAndRef() throws Exception {
        ToolOutputRef ref = new ToolOutputRef(
            "toolout_01",
            "ses_01",
            "toolu_01",
            "text/plain; charset=utf-8",
            "pending",
            "",
            "sha256:abc123",
            42L,
            Map.of("preview", "hello")
        );
        ToolResultSummary summary = new ToolResultSummary(
            "bash succeeded",
            "hello",
            false,
            0,
            false,
            42L,
            Map.of("toolName", "bash")
        );

        String refJson = mapper.writeValueAsString(ref);
        ToolOutputRef restoredRef = mapper.readValue(refJson, ToolOutputRef.class);
        assertEquals("toolout_01", restoredRef.refId());
        assertEquals("pending", restoredRef.storageKind());
        assertEquals("sha256:abc123", restoredRef.contentHash());

        String summaryJson = mapper.writeValueAsString(summary);
        ToolResultSummary restoredSummary = mapper.readValue(summaryJson, ToolResultSummary.class);
        assertEquals("bash succeeded", restoredSummary.title());
        assertEquals(0, restoredSummary.exitCode());
        assertEquals(42L, restoredSummary.outputBytes());

        String statusJson = mapper.writeValueAsString(ToolExecutionStatus.TIMED_OUT);
        assertEquals(ToolExecutionStatus.TIMED_OUT, mapper.readValue(statusJson, ToolExecutionStatus.class));
    }

    @Test
    void toolStartEventRoundTripKeepsDisplayAndTimingFields() throws Exception {
        AgentEvent event = new ToolStartEvent(
            "ses_01",
            "toolu_01",
            "msg_parent",
            "turn_01",
            "bash",
            "Bash",
            "echo hello",
            Map.of("command", "echo hello"),
            Instant.parse("2026-06-01T12:00:00Z"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"tool_start\""));
        ToolStartEvent start = assertInstanceOf(ToolStartEvent.class, restored);
        assertEquals("msg_parent", start.parentMessageId());
        assertEquals("turn_01", start.turnId());
        assertEquals("Bash", start.displayTitle());
        assertEquals(start.startedAt(), start.timestamp());
    }

    @Test
    void toolEndEventRoundTripKeepsStatusSummaryRefAndTimingFields() throws Exception {
        Instant startedAt = Instant.parse("2026-06-01T12:00:00Z");
        Instant endedAt = Instant.parse("2026-06-01T12:00:03Z");
        ToolResultSummary summary = new ToolResultSummary(
            "bash failed",
            "exit 2",
            true,
            2,
            false,
            128L,
            Map.of("toolName", "bash")
        );
        ToolOutputRef ref = new ToolOutputRef(
            "toolout_01",
            "ses_01",
            "toolu_01",
            "text/plain; charset=utf-8",
            "pending",
            "",
            "sha256:abc123",
            128L,
            Map.of("truncated", true)
        );
        AgentEvent event = new ToolEndEvent(
            "ses_01",
            "toolu_01",
            ToolExecutionStatus.FAILED,
            2,
            summary,
            ref,
            startedAt,
            endedAt,
            3000L,
            Map.of("interrupted", false),
            endedAt
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"tool_end\""));
        ToolEndEvent end = assertInstanceOf(ToolEndEvent.class, restored);
        assertEquals(ToolExecutionStatus.FAILED, end.status());
        assertEquals(2, end.exitCode());
        assertEquals("bash failed", end.resultSummary().title());
        assertEquals("toolout_01", end.resultRef().refId());
        assertEquals(end.endedAt(), end.timestamp());
    }

    @Test
    void permissionRequestEventRoundTripContainsRenderableToolContext() throws Exception {
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "command needs approval",
            Optional.empty(),
            Map.of("risk", "medium")
        );
        AgentEvent event = new PermissionRequestEvent(
            "ses_01",
            "toolu_01",
            "bash",
            "bash {command=rm -rf target}",
            "command needs approval",
            decision,
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"permission_request\""));
        assertTrue(json.contains("\"toolName\":\"bash\""));
        assertTrue(json.contains("\"renderedToolUse\":\"bash {command=rm -rf target}\""));
        assertInstanceOf(PermissionRequestEvent.class, restored);
        PermissionRequestEvent request = (PermissionRequestEvent) restored;
        assertEquals("bash", request.toolName());
        assertEquals(PermissionBehavior.ASK, request.decision().behavior());
    }

    @Test
    void permissionOptionRoundTripPreservesRememberUpdate() throws Exception {
        PermissionUpdate update = permissionUpdate(PermissionRuleSource.SESSION);
        PermissionOption option = new PermissionOption(
            "allow_remember_session",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "本次会话内允许相同工具调用。",
            Optional.of(update),
            Map.of("scope", "session")
        );

        String json = mapper.writeValueAsString(option);
        PermissionOption restored = mapper.readValue(json, PermissionOption.class);

        assertEquals(PermissionOptionKind.ALLOW_AND_REMEMBER, restored.kind());
        assertEquals(update, restored.permissionUpdate().orElseThrow());
        assertEquals("session", restored.metadata().get("scope"));
    }

    @Test
    void permissionResponseRoundTripPreservesKeyboardCancel() throws Exception {
        PermissionResponse response = new PermissionResponse(
            "ses_01",
            "perm_01",
            "cancel",
            true,
            Instant.parse("2026-06-01T12:00:01Z")
        );

        String json = mapper.writeValueAsString(response);
        PermissionResponse restored = mapper.readValue(json, PermissionResponse.class);

        assertEquals("perm_01", restored.requestId());
        assertEquals("cancel", restored.selectedOptionId());
        assertTrue(restored.fromKeyboardCancel());
    }

    @Test
    void permissionResponseEventRoundTripContainsRequestAndOption() throws Exception {
        AgentEvent event = new PermissionResponseEvent(
            "ses_01",
            "perm_toolu_01",
            "allow_once",
            false,
            Instant.parse("2026-06-01T12:00:01Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"permission_response\""));
        PermissionResponseEvent response = assertInstanceOf(PermissionResponseEvent.class, restored);
        assertEquals("ses_01", response.sessionId());
        assertEquals("perm_toolu_01", response.requestId());
        assertEquals("allow_once", response.selectedOptionId());
        assertEquals(false, response.fromKeyboardCancel());
    }

    @Test
    void structuredPermissionRequestEventRoundTripContainsOptionsAndDefaults() throws Exception {
        PermissionDecision policyDecision = new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "command needs approval",
            Optional.empty(),
            Map.of("risk", "destructive")
        );
        AgentEvent event = new PermissionRequestEvent(
            "ses_01",
            "perm_01",
            "toolu_01",
            "bash",
            "Bash 命令需要确认",
            "bash {command=rm -rf target}",
            policyDecision,
            List.of(
                new PermissionOption(
                    "allow_once",
                    PermissionOptionKind.ALLOW_ONCE,
                    "允许一次",
                    "仅允许当前工具调用。",
                    Optional.empty(),
                    Map.of()
                ),
                new PermissionOption(
                    "deny",
                    PermissionOptionKind.DENY,
                    "拒绝",
                    "拒绝当前工具调用。",
                    Optional.empty(),
                    Map.of()
                ),
                new PermissionOption(
                    "cancel",
                    PermissionOptionKind.CANCEL,
                    "取消",
                    "取消权限请求。",
                    Optional.empty(),
                    Map.of()
                )
            ),
            "deny",
            "cancel",
            Map.of("source", "policy"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"permission_request\""));
        PermissionRequestEvent request = assertInstanceOf(PermissionRequestEvent.class, restored);
        assertEquals("perm_01", request.requestId());
        assertEquals("Bash 命令需要确认", request.displayTitle());
        assertEquals(PermissionBehavior.ASK, request.policyDecision().behavior());
        assertEquals(3, request.options().size());
        assertEquals("deny", request.defaultOptionId());
        assertEquals("cancel", request.cancelOptionId());
        assertEquals("policy", request.metadata().get("source"));
    }

    @Test
    void structuredPermissionDecisionEventRoundTripContainsSelectedOptionAndAppliedUpdate() throws Exception {
        PermissionUpdate update = permissionUpdate(PermissionRuleSource.SESSION);
        PermissionDecision decision = new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.BASH_RISK,
            "allowed",
            Optional.empty(),
            Map.of("risk", "medium")
        );
        AgentEvent event = new PermissionDecisionEvent(
            "ses_01",
            "perm_01",
            "toolu_01",
            "bash",
            "allow_remember_session",
            decision,
            Optional.of(update),
            Map.of("updateStatus", "applied"),
            Instant.parse("2026-06-01T12:00:02Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"permission_decision\""));
        PermissionDecisionEvent permissionDecision = assertInstanceOf(PermissionDecisionEvent.class, restored);
        assertEquals("perm_01", permissionDecision.requestId());
        assertEquals("allow_remember_session", permissionDecision.selectedOptionId());
        assertEquals(update, permissionDecision.appliedUpdate().orElseThrow());
        assertEquals("applied", permissionDecision.metadata().get("updateStatus"));
    }

    @Test
    void permissionOptionRejectsRememberWithoutUpdate() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new PermissionOption(
            "allow_remember",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "缺少更新时应拒绝。",
            Optional.empty(),
            Map.of()
        ));
    }

    @Test
    void permissionOptionRejectsSystemRuleSourcesForRemember() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new PermissionOption(
            "allow_platform",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "不能写入平台规则。",
            Optional.of(permissionUpdate(PermissionRuleSource.PLATFORM)),
            Map.of()
        ));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new PermissionOption(
            "allow_cli",
            PermissionOptionKind.ALLOW_AND_REMEMBER,
            "允许并记住",
            "不能写入 CLI 覆盖规则。",
            Optional.of(permissionUpdate(PermissionRuleSource.CLI_OVERRIDE)),
            Map.of()
        ));
    }

    @Test
    void permissionRequestEventCanReadLegacyJson() throws Exception {
        String json = """
            {
              "type": "permission_request",
              "sessionId": "ses_01",
              "toolUseId": "toolu_01",
              "message": "legacy approval",
              "timestamp": "2026-06-01T12:00:00Z"
            }
            """;

        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        PermissionRequestEvent request = assertInstanceOf(PermissionRequestEvent.class, restored);
        assertEquals("toolu_01", request.toolUseId());
        assertEquals("legacy approval", request.message());
        assertEquals("unknown", request.toolName());
        assertEquals("", request.renderedToolUse());
        assertEquals("toolu_01", request.requestId());
        assertEquals("legacy approval", request.displayTitle());
        assertEquals(3, request.options().size());
        assertEquals("allow_once", request.defaultOptionId());
        assertEquals("cancel", request.cancelOptionId());
    }

    @Test
    void permissionDecisionEventCanReadLegacyJson() throws Exception {
        String json = """
            {
              "type": "permission_decision",
              "sessionId": "ses_01",
              "toolUseId": "toolu_01",
              "decision": {
                "behavior": "ALLOW",
                "reason": "TOOL_SPECIFIC",
                "message": "legacy allowed",
                "suggestedUpdate": null,
                "metadata": {}
              },
              "timestamp": "2026-06-01T12:00:00Z"
            }
            """;

        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        PermissionDecisionEvent decision = assertInstanceOf(PermissionDecisionEvent.class, restored);
        assertEquals("toolu_01", decision.toolUseId());
        assertEquals("unknown", decision.toolName());
        assertEquals("", decision.renderedToolUse());
        assertEquals(PermissionBehavior.ALLOW, decision.decision().behavior());
        assertEquals("toolu_01", decision.requestId());
        assertEquals("legacy", decision.selectedOptionId());
        assertTrue(decision.appliedUpdate().isEmpty());
    }

    private PermissionUpdate permissionUpdate(PermissionRuleSource source) {
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

    @Test
    void contentBlockRoundTripUsesSpecializedBlockTypes() throws Exception {
        ContentBlock block = new TextContentBlock("hello");

        String json = mapper.writeValueAsString(block);
        ContentBlock restored = mapper.readValue(json, ContentBlock.class);

        assertTrue(json.contains("\"type\":\"text\""));
        assertInstanceOf(TextContentBlock.class, restored);
        assertEquals("hello", ((TextContentBlock) restored).text());
    }

    @Test
    void allContentBlockKindsHaveSerializableRepresentations() throws Exception {
        assertContentBlockRoundTrip(new ThinkingContentBlock("reasoning"), ThinkingContentBlock.class, "thinking");
        assertContentBlockRoundTrip(
            new ToolCallContentBlock("toolu_01", "read", "{\"path\":\"README.md\"}"),
            ToolCallContentBlock.class,
            "tool_call"
        );
        assertContentBlockRoundTrip(
            new ToolResultContentBlock("toolu_01", "file text", false),
            ToolResultContentBlock.class,
            "tool_result"
        );
        assertContentBlockRoundTrip(new ErrorContentBlock("err_01", "bad input"), ErrorContentBlock.class, "error");
        assertContentBlockRoundTrip(
            new AttachmentContentBlock("file_01", "diagram.png", "image/png"),
            AttachmentContentBlock.class,
            "attachment"
        );
    }

    @Test
    void contentBlockCanReadLegacyKindBasedJson() throws Exception {
        String json = """
            {
              "kind": "TEXT",
              "text": "legacy text",
              "metadata": {
                "source": "old-session"
              }
            }
            """;

        ContentBlock restored = mapper.readValue(json, ContentBlock.class);

        assertEquals(ContentBlockKind.TEXT, restored.kind());
        assertEquals("legacy text", restored.text());
        assertEquals("old-session", restored.metadata().get("source"));
    }

    @Test
    void lyPiExceptionRoundTripKeepsCategoryAndMessage() throws Exception {
        LyPiException exception = new ToolValidationException(
            "err_01",
            ErrorSeverity.WARNING,
            false,
            "bad tool input"
        );

        String json = mapper.writeValueAsString(exception);
        LyPiException restored = mapper.readValue(json, LyPiException.class);

        assertTrue(json.contains("\"type\":\"tool_validation\""));
        assertInstanceOf(ToolValidationException.class, restored);
        assertEquals("bad tool input", restored.getMessage());
        assertEquals(ErrorSeverity.WARNING, restored.severity());
    }

    @Test
    void tuiViewModelRoundTripKeepsLightweightBlockTypes() throws Exception {
        TuiViewModel viewModel = new TuiViewModel(
            List.of(
                new TuiMessageBlock(
                    "block_msg",
                    "msg_01",
                    "assistant",
                    "hello",
                    false
                ),
                new TuiThinkingBlock("block_thinking", "msg_01", "considering", true, true),
                new TuiToolBlock(
                    "block_tool",
                    "msg_01",
                    "toolu_01",
                    "bash",
                    TuiToolState.RUNNING,
                    "Bash",
                    "stdout: hello",
                    true
                ),
                new TuiErrorBlock("block_error", "boom")
            ),
            new StatusBarState(
                "ses_01",
                "gpt-5.4",
                "running",
                "main",
                "ly-pi",
                "leaf_01",
                "1234/200000tok",
                true
            ),
            "retrying attempt 2",
            List.of(new SessionFileView(Path.of("src/App.java"), Set.of(), Instant.parse("2026-06-01T12:00:00Z"), Map.of())),
            Optional.of(new PermissionPromptView("perm_toolu_01", "toolu_01", "Need approval", "allow_once", "allow_once", "cancel")),
            Optional.of(new DiffView(
                "1 file changed",
                List.of(new GitDiffFileView(
                    Path.of("src/App.java"),
                    GitDiffStatus.MODIFIED,
                    "Modified",
                    Map.of("porcelain", " M src/App.java")
                )),
                "diff --git a/src/App.java b/src/App.java\n+hello\n",
                true,
                Map.of("snapshotHash", "sha256:diff")
            ))
        );

        String json = mapper.writeValueAsString(viewModel);
        TuiViewModel restored = mapper.readValue(json, TuiViewModel.class);

        assertTrue(json.contains("\"type\":\"message\""));
        assertTrue(json.contains("\"type\":\"thinking\""));
        assertTrue(json.contains("\"type\":\"tool\""));
        assertTrue(json.contains("\"type\":\"error\""));
        assertInstanceOf(TuiMessageBlock.class, restored.blocks().get(0));
        assertInstanceOf(TuiThinkingBlock.class, restored.blocks().get(1));
        assertInstanceOf(TuiToolBlock.class, restored.blocks().get(2));
        assertInstanceOf(TuiErrorBlock.class, restored.blocks().get(3));
        TuiToolBlock tool = (TuiToolBlock) restored.blocks().get(2);
        assertEquals("ly-pi", restored.statusBar().cwd());
        assertEquals("leaf_01", restored.statusBar().branchLeafId());
        assertEquals("1234/200000tok", restored.statusBar().budget());
        assertTrue(restored.statusBar().hasInterruptibleTool());
        assertEquals("msg_01", tool.messageId());
        assertEquals(TuiToolState.RUNNING, tool.state());
        assertEquals("stdout: hello", tool.details());
        assertTrue(tool.active());
        assertEquals("retrying attempt 2", restored.runtimeLine());
        assertTrue(restored.files().getFirst().path().endsWith(Path.of("src/App.java")));
        assertEquals("perm_toolu_01", restored.permissionPrompt().orElseThrow().requestId());
        assertEquals("cancel", restored.permissionPrompt().orElseThrow().cancelOptionId());
        DiffView diffView = restored.diffView().orElseThrow();
        assertEquals("1 file changed", diffView.summary());
        assertTrue(diffView.files().getFirst().path().endsWith(Path.of("src/App.java")));
        assertEquals(GitDiffStatus.MODIFIED, diffView.files().getFirst().status());
        assertTrue(diffView.patch().contains("+hello"));
        assertTrue(diffView.truncated());
        assertEquals("sha256:diff", diffView.metadata().get("snapshotHash"));
    }

    @Test
    void statusBarStateReadsLegacyFourFieldJson() throws Exception {
        StatusBarState restored = mapper.readValue(
            """
                {
                  "sessionId": "ses_legacy",
                  "model": "gpt-5.4",
                  "mode": "execute",
                  "permissionMode": "default_execute"
                }
                """,
            StatusBarState.class
        );

        assertEquals("ses_legacy", restored.sessionId());
        assertEquals("gpt-5.4", restored.model());
        assertEquals("execute", restored.mode());
        assertEquals("default_execute", restored.permissionMode());
        assertEquals("", restored.cwd());
        assertEquals("", restored.branchLeafId());
        assertEquals("", restored.budget());
        assertFalse(restored.hasInterruptibleTool());
    }

    @Test
    void tuiViewModelReadsLegacyJsonWithoutRuntimeLineAndToolDetails() throws Exception {
        TuiViewModel restored = mapper.readValue(
            """
                {
                  "blocks": [
                    {
                      "type": "tool",
                      "blockId": "block_tool",
                      "messageId": "msg_01",
                      "toolUseId": "toolu_01",
                      "toolName": "bash",
                      "state": "RUNNING",
                      "label": "Bash",
                      "active": true
                    }
                  ],
                  "statusBar": {
                    "sessionId": "ses_01",
                    "model": "gpt-5.4",
                    "mode": "running",
                    "permissionMode": "main"
                  },
                  "files": [],
                  "permissionPrompt": null,
                  "diffView": null
                }
                """,
            TuiViewModel.class
        );

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, restored.blocks().getFirst());
        assertEquals("", restored.runtimeLine());
        assertEquals("", tool.details());
    }

    private <T extends ContentBlock> void assertContentBlockRoundTrip(
        ContentBlock block,
        Class<T> expectedType,
        String expectedTypeName
    ) throws Exception {
        String json = mapper.writeValueAsString(block);
        ContentBlock restored = mapper.readValue(json, ContentBlock.class);

        assertTrue(json.contains("\"type\":\"" + expectedTypeName + "\""));
        assertInstanceOf(expectedType, restored);
        assertEquals(block.kind(), restored.kind());
    }
}
