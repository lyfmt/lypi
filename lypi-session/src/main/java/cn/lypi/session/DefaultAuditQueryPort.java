package cn.lypi.session;

import cn.lypi.contracts.audit.AuditQuery;
import cn.lypi.contracts.audit.AuditQueryPort;
import cn.lypi.contracts.audit.AuditRecord;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class DefaultAuditQueryPort implements AuditQueryPort {
    private final JsonlAuditStore store;

    public DefaultAuditQueryPort(Path cwd) {
        this(new JsonlAuditStore(cwd));
    }

    public DefaultAuditQueryPort(JsonlAuditStore store) {
        this.store = store;
    }

    /**
     * 查询可追责审计记录。
     */
    @Override
    public List<AuditRecord> query(AuditQuery query) {
        return store.readAll().stream()
            .filter(record -> query.sessionId().map(sessionId -> Objects.equals(sessionId, record.sessionId())).orElse(true))
            .filter(record -> query.entryId().map(entryId -> Objects.equals(entryId, record.entryId())).orElse(true))
            .filter(record -> query.kind().map(kind -> kind == record.kind()).orElse(true))
            .filter(record -> query.toolUseId().map(toolUseId -> record.toolUseId().filter(toolUseId::equals).isPresent()).orElse(true))
            .filter(record -> query.messageId().map(messageId -> record.messageId().filter(messageId::equals).isPresent()).orElse(true))
            .toList();
    }
}
