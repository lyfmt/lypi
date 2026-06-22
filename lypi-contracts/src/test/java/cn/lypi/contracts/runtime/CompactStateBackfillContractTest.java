package cn.lypi.contracts.runtime;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.resource.ResourceSnapshot;
import cn.lypi.contracts.skill.SkillIndex;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class CompactStateBackfillContractTest {
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
