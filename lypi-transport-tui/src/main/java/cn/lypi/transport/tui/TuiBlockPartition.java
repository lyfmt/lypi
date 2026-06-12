package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiBlock;
import java.util.List;

record TuiBlockPartition(List<TuiBlock> finalizedBlocks, List<TuiBlock> liveBlocks) {
    TuiBlockPartition {
        finalizedBlocks = List.copyOf(finalizedBlocks);
        liveBlocks = List.copyOf(liveBlocks);
    }
}
