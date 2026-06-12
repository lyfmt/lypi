package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import java.util.ArrayList;
import java.util.List;

final class TuiBlockPartitioner {
    TuiBlockPartition partition(List<TuiBlock> blocks) {
        List<TuiBlock> finalizedBlocks = new ArrayList<>();
        List<TuiBlock> liveBlocks = new ArrayList<>();
        boolean liveTailStarted = false;
        for (TuiBlock block : blocks) {
            if (!liveTailStarted && finalized(block)) {
                finalizedBlocks.add(block);
            } else {
                liveTailStarted = true;
                liveBlocks.add(block);
            }
        }
        return new TuiBlockPartition(finalizedBlocks, liveBlocks);
    }

    private boolean finalized(TuiBlock block) {
        return switch (block) {
            case TuiMessageBlock message -> !message.streaming();
            case TuiThinkingBlock thinking -> !thinking.streaming();
            case TuiToolBlock tool -> !tool.active();
            case TuiErrorBlock ignored -> true;
        };
    }
}
