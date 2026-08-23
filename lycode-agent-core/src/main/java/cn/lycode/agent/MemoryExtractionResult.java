package cn.lycode.agent;

import cn.lycode.contracts.memory.MemoryCandidate;
import cn.lycode.contracts.memory.MemoryWriteRequest;
import java.util.List;
import java.util.Optional;

public record MemoryExtractionResult(
    List<MemoryCandidate> candidates,
    List<MemoryWriteRequest> writeRequests,
    List<String> skippedReasons,
    Optional<String> failureReason
) {}
