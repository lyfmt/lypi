package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ToolProgressKind;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.ErrorContentBlock;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.error.ErrorSeverity;
import cn.lypi.contracts.error.LyPiException;
import cn.lypi.contracts.error.ToolValidationException;
import cn.lypi.contracts.event.AgentEvent;
import cn.lypi.contracts.event.EventEnvelope;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import cn.lypi.contracts.security.PermissionResponse;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ContractSerializationTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

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
