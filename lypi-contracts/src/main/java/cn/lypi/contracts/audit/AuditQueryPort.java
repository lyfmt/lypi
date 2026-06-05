package cn.lypi.contracts.audit;

import java.util.List;

public interface AuditQueryPort {
    /**
     * 查询可追责审计记录。
     */
    List<AuditRecord> query(AuditQuery query);
}
