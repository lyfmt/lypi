package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.audit.AuditKind;
import cn.lypi.contracts.audit.AuditQuery;
import cn.lypi.contracts.audit.AuditRecord;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultAuditQueryPortTest {
    @TempDir
    Path tempDir;

    @Test
    void queryFiltersAuditRecordsByStableFieldsAndDetails() {
        JsonlAuditStore store = new JsonlAuditStore(tempDir);
        store.append(new AuditRecord(
            "audit_tool",
            "ses_main",
            "entry_tool",
            AuditKind.TOOL_USE,
            Map.of("toolUseId", "toolu_01", "messageId", "msg_01")
        ));
        store.append(new AuditRecord(
            "audit_permission",
            "ses_main",
            "entry_permission",
            AuditKind.PERMISSION_DECISION,
            Map.of("toolUseId", "toolu_01", "messageId", "msg_01")
        ));
        store.append(new AuditRecord(
            "audit_other",
            "ses_other",
            "entry_other",
            AuditKind.TOOL_USE,
            Map.of("toolUseId", "toolu_02", "messageId", "msg_02")
        ));

        DefaultAuditQueryPort queryPort = new DefaultAuditQueryPort(store);

        assertThat(queryPort.query(new AuditQuery(
            Optional.of("ses_main"),
            Optional.empty(),
            Optional.of("toolu_01"),
            Optional.empty(),
            Optional.of(AuditKind.PERMISSION_DECISION)
        )))
            .extracting(AuditRecord::auditId)
            .containsExactly("audit_permission");
        assertThat(queryPort.query(new AuditQuery(
            Optional.empty(),
            Optional.of("entry_tool"),
            Optional.empty(),
            Optional.of("msg_01"),
            Optional.empty()
        )))
            .extracting(AuditRecord::auditId)
            .containsExactly("audit_tool");
    }

    @Test
    void publicConstructorUsesDefaultAuditFileUnderCwd() {
        JsonlAuditStore store = new JsonlAuditStore(tempDir);
        store.append(new AuditRecord("audit_01", "ses_main", "entry_01", AuditKind.MEMORY_WRITE, Map.of()));

        DefaultAuditQueryPort queryPort = new DefaultAuditQueryPort(tempDir);

        assertThat(queryPort.query(new AuditQuery(
            Optional.of("ses_main"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        )))
            .extracting(AuditRecord::auditId)
            .containsExactly("audit_01");
    }
}
