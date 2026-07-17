package cn.lypi.transport.tui;

import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiErrorBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.util.List;

record TuiTranscriptPartition(List<TuiBlock> history, List<TuiBlock> live) {
    TuiTranscriptPartition {
        history = List.copyOf(history);
        live = List.copyOf(live);
    }
}

final class TuiTranscriptPartitioner {
    TuiTranscriptPartition partition(List<TuiBlock> blocks) {
        int liveStart = 0;
        while (liveStart < blocks.size() && stable(blocks.get(liveStart))) {
            liveStart++;
        }
        return new TuiTranscriptPartition(
            blocks.subList(0, liveStart),
            blocks.subList(liveStart, blocks.size())
        );
    }

    private boolean stable(TuiBlock block) {
        return switch (block) {
            case TuiMessageBlock message -> !message.streaming();
            case TuiThinkingBlock thinking -> !thinking.streaming();
            case TuiToolBlock tool -> terminal(tool.state()) && !tool.active();
            case TuiErrorBlock ignored -> true;
        };
    }

    private boolean terminal(TuiToolState state) {
        return state == TuiToolState.DONE
            || state == TuiToolState.FAILED
            || state == TuiToolState.CANCELLED;
    }
}
