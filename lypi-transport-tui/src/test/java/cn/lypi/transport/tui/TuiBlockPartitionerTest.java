package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiBlockPartitionerTest {
    @Test
    void finalizedPrefixMovesToHistoryAndStreamingTailStaysLive() {
        List<TuiBlock> blocks = List.of(
            new TuiMessageBlock("u1", "m1", "user", "show files", false),
            new TuiToolBlock("tool:t1", "m2", "t1", "read", TuiToolState.DONE, "AGENTS.md", "done", false),
            new TuiMessageBlock("a1", "m2", "assistant", "partial", true)
        );

        TuiBlockPartition partition = new TuiBlockPartitioner().partition(blocks);

        assertEquals(List.of("u1", "tool:t1"), blockIds(partition.finalizedBlocks()));
        assertEquals(List.of("a1"), blockIds(partition.liveBlocks()));
    }

    @Test
    void finalizedHistoryAfterLiveBlockRemainsInViewportToPreserveOrdering() {
        List<TuiBlock> blocks = List.of(
            new TuiMessageBlock("u1", "m1", "user", "show files", false),
            new TuiMessageBlock("a1", "m2", "assistant", "partial", true),
            new TuiToolBlock("tool:t1", "m2", "t1", "read", TuiToolState.DONE, "AGENTS.md", "done", false)
        );

        TuiBlockPartition partition = new TuiBlockPartitioner().partition(blocks);

        assertEquals(List.of("u1"), blockIds(partition.finalizedBlocks()));
        assertEquals(List.of("a1", "tool:t1"), blockIds(partition.liveBlocks()));
    }

    private List<String> blockIds(List<TuiBlock> blocks) {
        return blocks.stream().map(TuiBlock::blockId).toList();
    }
}
