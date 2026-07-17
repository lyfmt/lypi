package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.List;
import org.junit.jupiter.api.Test;

class TuiTranscriptPartitionerTest {
    @Test
    void keepsStableBlocksAfterFirstLiveBlockInLiveTail() {
        TuiTranscriptPartition partition = new TuiTranscriptPartitioner().partition(List.of(
            message("old-user", false),
            tool("done-tool", TuiToolState.DONE, false),
            message("streaming", true),
            tool("later-done", TuiToolState.DONE, false)
        ));

        assertEquals(List.of("old-user", "done-tool"), blockIds(partition.history()));
        assertEquals(List.of("streaming", "later-done"), blockIds(partition.live()));
    }

    @Test
    void putsCompleteRestoredProjectionInHistory() {
        List<TuiBlock> blocks = List.of(
            message("user", false),
            new TuiThinkingBlock("thinking", "message-thinking", "done", false, true),
            tool("done", TuiToolState.DONE, false),
            tool("failed", TuiToolState.FAILED, false),
            tool("cancelled", TuiToolState.CANCELLED, false),
            new TuiErrorBlock("error", "failed")
        );

        TuiTranscriptPartition partition = new TuiTranscriptPartitioner().partition(blocks);

        assertEquals(blockIds(blocks), blockIds(partition.history()));
        assertEquals(List.of(), partition.live());
    }

    @Test
    void treatsStreamingAndNonterminalOrActiveToolsAsLive() {
        assertLive(message("streaming-message", true));
        assertLive(new TuiThinkingBlock("streaming-thinking", "message-thinking", "working", true, false));
        assertLive(tool("pending", TuiToolState.PENDING, false));
        assertLive(tool("running", TuiToolState.RUNNING, false));
        assertLive(tool("active-done", TuiToolState.DONE, true));
    }

    @Test
    void stablePrefixCanRegressWhenAPreviouslyStableBlockBecomesLive() {
        TuiTranscriptPartitioner partitioner = new TuiTranscriptPartitioner();

        TuiTranscriptPartition stable = partitioner.partition(List.of(
            message("user", false),
            message("assistant", false)
        ));
        TuiTranscriptPartition regressed = partitioner.partition(List.of(
            message("user", false),
            message("assistant", true)
        ));

        assertEquals(List.of("user", "assistant"), blockIds(stable.history()));
        assertEquals(List.of("user"), blockIds(regressed.history()));
        assertEquals(List.of("assistant"), blockIds(regressed.live()));
    }

    private TuiMessageBlock message(String blockId, boolean streaming) {
        return new TuiMessageBlock(blockId, "message-" + blockId, "assistant", blockId, streaming);
    }

    private TuiToolBlock tool(String blockId, TuiToolState state, boolean active) {
        return new TuiToolBlock(blockId, "message-" + blockId, "use-" + blockId, "Bash", state, blockId, active);
    }

    private List<String> blockIds(List<TuiBlock> blocks) {
        return blocks.stream().map(TuiBlock::blockId).toList();
    }

    private void assertLive(TuiBlock block) {
        TuiTranscriptPartition partition = new TuiTranscriptPartitioner().partition(List.of(block));
        assertEquals(List.of(), partition.history());
        assertEquals(List.of(block), partition.live());
    }
}
