package cn.lypi.session;

import java.util.UUID;

/**
 * 生成 session entry id。
 */
final class SessionEntryIds {
    private SessionEntryIds() {
    }

    /**
     * 生成新的 entry id。
     */
    static String newEntryId() {
        return "entry_" + UUID.randomUUID().toString().replace("-", "");
    }
}
