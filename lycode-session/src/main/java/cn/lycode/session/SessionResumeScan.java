package cn.lycode.session;

import cn.lycode.contracts.session.SessionHeader;
import java.nio.file.Path;
import java.time.Instant;

record SessionResumeScan(
    SessionHeader header,
    Path path,
    String leafId,
    Instant modified,
    int messageCount,
    String firstMessage,
    String allMessagesText
) {}
