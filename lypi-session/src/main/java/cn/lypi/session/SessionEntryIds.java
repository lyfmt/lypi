package cn.lypi.session;

import java.util.UUID;

final class SessionEntryIds {
    private SessionEntryIds() {
    }

    static String newEntryId() {
        return "entry_" + UUID.randomUUID().toString().replace("-", "");
    }
}
