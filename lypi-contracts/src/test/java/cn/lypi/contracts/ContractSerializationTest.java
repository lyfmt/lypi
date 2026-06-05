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
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.model.TokenUsage;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
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
    void assistantTextDeltaRoundTripKeepsRoleKindAndBlockKind() throws Exception {
        AgentEvent event = new MessageDeltaEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            "msg_01:text:0",
            ContentBlockKind.TEXT,
            "hello",
            false,
            Map.of("source", "assistant"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        String json = mapper.writeValueAsString(event);
        AgentEvent restored = mapper.readValue(json, AgentEvent.class);

        assertTrue(json.contains("\"type\":\"message_delta\""));
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, restored);
        assertEquals(MessageRole.ASSISTANT, delta.role());
        assertEquals(MessageKind.TEXT, delta.kind());
        assertEquals("msg_01:text:0", delta.blockId());
        assertEquals(ContentBlockKind.TEXT, delta.blockKind());
        assertEquals("hello", delta.delta());
        assertEquals(false, delta.isFinal());
        assertEquals("assistant", delta.metadata().get("source"));
    }

    @Test
    void assistantThinkingDeltaRoundTripKeepsThinkingBlockKind() throws Exception {
        AgentEvent event = new MessageDeltaEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.THINKING,
            "msg_01:thinking:0",
            ContentBlockKind.THINKING,
            "reasoning",
            false,
            Map.of(),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        MessageDeltaEvent restored = assertInstanceOf(
            MessageDeltaEvent.class,
            mapper.readValue(mapper.writeValueAsString(event), AgentEvent.class)
        );

        assertEquals(MessageKind.THINKING, restored.kind());
        assertEquals(ContentBlockKind.THINKING, restored.blockKind());
        assertEquals("reasoning", restored.delta());
    }

    @Test
    void assistantToolCallMetadataRoundTripKeepsStructuredFields() throws Exception {
        AgentEvent event = new MessageDeltaEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            "toolu_01",
            ContentBlockKind.TOOL_CALL,
            "",
            true,
            Map.of(
                "toolUseId", "toolu_01",
                "toolName", "read",
                "partialInput", Map.of("path", "README.md"),
                "complete", true
            ),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        MessageDeltaEvent restored = assertInstanceOf(
            MessageDeltaEvent.class,
            mapper.readValue(mapper.writeValueAsString(event), AgentEvent.class)
        );

        assertEquals(ContentBlockKind.TOOL_CALL, restored.blockKind());
        assertEquals("toolu_01", restored.metadata().get("toolUseId"));
        assertEquals("read", restored.metadata().get("toolName"));
        assertEquals(Map.of("path", "README.md"), restored.metadata().get("partialInput"));
        assertEquals(true, restored.metadata().get("complete"));
    }

    @Test
    void toolResultMessageEndRoundTripKeepsBlocksUsageAndStopReason() throws Exception {
        AgentEvent event = new MessageEndEvent(
            "ses_01",
            "msg_02",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new MessageBlockSnapshot(
                "toolu_01",
                ContentBlockKind.TOOL_RESULT,
                "file text",
                Map.of("toolUseId", "toolu_01", "isError", false, "resultRefId", "res_01")
            )),
            Optional.of(new TokenUsage(12, 7, 1, 2)),
            Optional.of("tool_result"),
            Map.of("source", "tool-runtime"),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        MessageEndEvent restored = assertInstanceOf(
            MessageEndEvent.class,
            mapper.readValue(mapper.writeValueAsString(event), AgentEvent.class)
        );

        assertEquals(MessageRole.TOOL_RESULT, restored.role());
        assertEquals(MessageKind.TOOL_RESULT, restored.kind());
        assertEquals(Optional.of("tool_result"), restored.stopReason());
        assertEquals(Optional.of(new TokenUsage(12, 7, 1, 2)), restored.usage());
        assertEquals("file text", restored.blocks().getFirst().text());
        assertEquals("res_01", restored.blocks().getFirst().metadata().get("resultRefId"));
    }

    @Test
    void errorAndAttachmentBlocksRoundTripInsideMessageEnd() throws Exception {
        AgentEvent event = new MessageEndEvent(
            "ses_01",
            "msg_03",
            MessageRole.ASSISTANT,
            MessageKind.ERROR,
            List.of(
                new MessageBlockSnapshot(
                    "err_01",
                    ContentBlockKind.ERROR,
                    "bad input",
                    Map.of("errorId", "err_01", "message", "bad input", "severity", "WARNING")
                ),
                new MessageBlockSnapshot(
                    "file_01",
                    ContentBlockKind.ATTACHMENT,
                    "diagram.png",
                    Map.of("attachmentId", "file_01", "name", "diagram.png", "mimeType", "image/png")
                )
            ),
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            Instant.parse("2026-06-01T12:00:00Z")
        );

        MessageEndEvent restored = assertInstanceOf(
            MessageEndEvent.class,
            mapper.readValue(mapper.writeValueAsString(event), AgentEvent.class)
        );

        assertEquals(ContentBlockKind.ERROR, restored.blocks().get(0).blockKind());
        assertEquals("err_01", restored.blocks().get(0).metadata().get("errorId"));
        assertEquals(ContentBlockKind.ATTACHMENT, restored.blocks().get(1).blockKind());
        assertEquals("image/png", restored.blocks().get(1).metadata().get("mimeType"));
    }

    @Test
    void summaryAndProgressMessagesUseMessageKindWithExistingBlockKinds() throws Exception {
        MessageStartEvent summary = new MessageStartEvent(
            "ses_01",
            "msg_summary",
            MessageRole.SYSTEM_LOCAL,
            MessageKind.SUMMARY,
            Map.of("summarySource", "compaction"),
            Instant.parse("2026-06-01T12:00:00Z")
        );
        MessageDeltaEvent progress = new MessageDeltaEvent(
            "ses_01",
            "msg_progress",
            MessageRole.SYSTEM_LOCAL,
            MessageKind.PROGRESS,
            "msg_progress:progress:0",
            ContentBlockKind.TEXT,
            "3/10",
            false,
            Map.of("progressKind", "COUNTER", "current", 3, "total", 10),
            Instant.parse("2026-06-01T12:00:01Z")
        );

        MessageStartEvent restoredSummary = assertInstanceOf(
            MessageStartEvent.class,
            mapper.readValue(mapper.writeValueAsString(summary), AgentEvent.class)
        );
        MessageDeltaEvent restoredProgress = assertInstanceOf(
            MessageDeltaEvent.class,
            mapper.readValue(mapper.writeValueAsString(progress), AgentEvent.class)
        );

        assertEquals(MessageKind.SUMMARY, restoredSummary.kind());
        assertEquals("compaction", restoredSummary.metadata().get("summarySource"));
        assertEquals(MessageKind.PROGRESS, restoredProgress.kind());
        assertEquals(ContentBlockKind.TEXT, restoredProgress.blockKind());
        assertEquals("COUNTER", restoredProgress.metadata().get("progressKind"));
    }

    @Test
    void finalDeltaOnlyEndsCurrentBlockAndMessageEndEndsWholeMessage() throws Exception {
        MessageDeltaEvent finalBlock = new MessageDeltaEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            "toolu_01",
            ContentBlockKind.TOOL_CALL,
            "",
            true,
            Map.of("toolUseId", "toolu_01", "toolName", "read", "partialInput", Map.of(), "complete", true),
            Instant.parse("2026-06-01T12:00:00Z")
        );
        MessageEndEvent messageEnd = new MessageEndEvent(
            "ses_01",
            "msg_01",
            MessageRole.ASSISTANT,
            MessageKind.TEXT,
            List.of(),
            Optional.empty(),
            Optional.of("stop"),
            Map.of(),
            Instant.parse("2026-06-01T12:00:01Z")
        );

        MessageDeltaEvent restoredDelta = assertInstanceOf(
            MessageDeltaEvent.class,
            mapper.readValue(mapper.writeValueAsString(finalBlock), AgentEvent.class)
        );
        MessageEndEvent restoredEnd = assertInstanceOf(
            MessageEndEvent.class,
            mapper.readValue(mapper.writeValueAsString(messageEnd), AgentEvent.class)
        );

        assertEquals(true, restoredDelta.isFinal());
        assertEquals("toolu_01", restoredDelta.blockId());
        assertEquals(Optional.of("stop"), restoredEnd.stopReason());
        assertEquals("msg_01", restoredEnd.messageId());
    }

    @Test
    void messageEventsCanReadLegacyJsonWithSemanticDefaults() throws Exception {
        String startJson = """
            {
              "type": "message_start",
              "sessionId": "ses_01",
              "messageId": "msg_01",
              "timestamp": "2026-06-01T12:00:00Z"
            }
            """;
        String deltaJson = """
            {
              "type": "message_delta",
              "sessionId": "ses_01",
              "messageId": "msg_01",
              "delta": "legacy text",
              "timestamp": "2026-06-01T12:00:01Z"
            }
            """;
        String endJson = """
            {
              "type": "message_end",
              "sessionId": "ses_01",
              "messageId": "msg_01",
              "timestamp": "2026-06-01T12:00:02Z"
            }
            """;

        MessageStartEvent start = assertInstanceOf(MessageStartEvent.class, mapper.readValue(startJson, AgentEvent.class));
        MessageDeltaEvent delta = assertInstanceOf(MessageDeltaEvent.class, mapper.readValue(deltaJson, AgentEvent.class));
        MessageEndEvent end = assertInstanceOf(MessageEndEvent.class, mapper.readValue(endJson, AgentEvent.class));

        assertEquals(MessageRole.ASSISTANT, start.role());
        assertEquals(MessageKind.TEXT, start.kind());
        assertTrue(start.metadata().isEmpty());
        assertEquals(MessageRole.ASSISTANT, delta.role());
        assertEquals(MessageKind.TEXT, delta.kind());
        assertEquals("msg_01:text:0", delta.blockId());
        assertEquals(ContentBlockKind.TEXT, delta.blockKind());
        assertEquals(false, delta.isFinal());
        assertTrue(delta.metadata().isEmpty());
        assertEquals(MessageRole.ASSISTANT, end.role());
        assertEquals(MessageKind.TEXT, end.kind());
        assertTrue(end.blocks().isEmpty());
        assertTrue(end.usage().isEmpty());
        assertTrue(end.stopReason().isEmpty());
        assertTrue(end.metadata().isEmpty());
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
