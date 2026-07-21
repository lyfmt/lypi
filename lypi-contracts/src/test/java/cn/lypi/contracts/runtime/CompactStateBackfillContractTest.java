package cn.lypi.contracts.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactStateBackfillContractTest {
    private final ObjectMapper mapper = new ObjectMapper()
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule());

    @Test
    void nonePortReturnsNoItems() {
        CompactStateBackfillRequest request = new CompactStateBackfillRequest(
            "session-1",
            Path.of("."),
            new ResourceSnapshot(List.of(), List.of(), new SkillIndex(List.of(), List.of()), List.of(), List.of(), List.of()),
            new ToolRegistrySnapshot(List.of()),
            List.of()
        );

        assertTrue(CompactStateBackfillPort.none().backfill(request).isEmpty());
    }

    @Test
    void requestNormalizesNullLeafEntryId() {
        CompactStateBackfillRequest request = new CompactStateBackfillRequest(
            "session-1",
            null,
            Path.of("."),
            null,
            null,
            List.of()
        );

        assertTrue(request.leafEntryId().isEmpty());
    }

    @Test
    void requestKeepsTargetLeafEntryId() {
        CompactStateBackfillRequest request = new CompactStateBackfillRequest(
            "session-1",
            Optional.of("entry-leaf"),
            Path.of("."),
            null,
            null,
            List.of()
        );

        assertEquals(Optional.of("entry-leaf"), request.leafEntryId());
    }

    @Test
    void requestJsonRoundTripsLeafEntryId() throws Exception {
        CompactStateBackfillRequest request = new CompactStateBackfillRequest(
            "session-1",
            Optional.of("entry-leaf"),
            Path.of("."),
            null,
            null,
            List.of()
        );

        CompactStateBackfillRequest restored = mapper.readValue(
            mapper.writeValueAsString(request),
            CompactStateBackfillRequest.class
        );

        assertEquals(Optional.of("entry-leaf"), restored.leafEntryId());
    }

    @Test
    void requestJsonDefaultsMissingLeafEntryIdForOldPayloads() throws Exception {
        String json = """
            {
              "sessionId": "session-1",
              "cwd": ".",
              "resourceSnapshot": null,
              "toolRegistry": null,
              "skillMentions": []
            }
            """;

        CompactStateBackfillRequest restored = mapper.readValue(json, CompactStateBackfillRequest.class);

        assertTrue(restored.leafEntryId().isEmpty());
    }

    @Test
    void itemNormalizesNullMetadata() {
        CompactStateBackfillItem item = new CompactStateBackfillItem(
            "compact-agent-state",
            "Agent State",
            "body",
            null
        );

        assertTrue(item.metadata().isEmpty());
    }
}
