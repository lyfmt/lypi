package cn.lypi.agent;

import cn.lypi.contracts.memory.MemoryCandidate;
import cn.lypi.contracts.memory.MemoryWriteRequest;
import java.util.List;
import java.util.Optional;

public record MemoryExtractionResult(
    List<MemoryCandidate> candidates,
    List<MemoryWriteRequest> writeRequests,
    List<String> skippedReasons,
    Optional<String> failureReason
) {}
