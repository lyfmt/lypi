package cn.lypi.contracts.session;

import java.time.Instant;

public interface SessionEntry {
    String id();

    String parentId();

    Instant timestamp();
}
