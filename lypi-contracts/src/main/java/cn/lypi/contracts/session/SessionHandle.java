package cn.lypi.contracts.session;

import java.nio.file.Path;
import java.util.Map;

public record SessionHandle(
    String sessionId,
    Path sessionFile,
    String leafId,
    Map<String, SessionEntry> byId
) {}

