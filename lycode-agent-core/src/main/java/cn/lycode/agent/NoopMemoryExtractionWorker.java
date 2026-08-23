package cn.lycode.agent;

import cn.lycode.contracts.agent.TurnState;
import java.util.List;
import java.util.Optional;

public final class NoopMemoryExtractionWorker implements MemoryExtractionWorker {
    @Override
    public MemoryExtractionResult extractAfterTurn(TurnState state) {
        return new MemoryExtractionResult(List.of(), List.of(), List.of(), Optional.empty());
    }
}
