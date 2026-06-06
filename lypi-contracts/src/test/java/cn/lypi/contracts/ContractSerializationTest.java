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
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CustomEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionInfoEntry;
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
    void sessionEntriesRoundTripOnlyForConversationPathFacts() throws Exception {
        Instant now = Instant.parse("2026-06-01T12:00:00Z");
        List<SessionEntry> entries = List.of(
            new CustomEntry("entry-custom", null, "demo.extension", Map.of("enabled", true), now),
            new BranchSummaryEntry("entry-branch", "entry-custom", "branch summary", now),
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
