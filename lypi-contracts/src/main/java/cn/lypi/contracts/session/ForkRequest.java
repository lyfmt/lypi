package cn.lypi.contracts.session;

import java.nio.file.Path;

public record ForkRequest(
    String sourceSessionId,
    String forkPointEntryId,
    Path targetCwd,
    String reason
) {}

