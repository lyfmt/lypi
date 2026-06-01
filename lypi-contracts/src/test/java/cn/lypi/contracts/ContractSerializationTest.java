package cn.lypi.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.context.ContentBlock;
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
import cn.lypi.contracts.event.TurnStartEvent;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
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
