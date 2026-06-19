package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionRule;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 保存当前运行时内的会话级权限规则。
 */
final class RuntimePermissionRuleStore {
    private static final String UNKNOWN_SESSION_ID = "session_unknown";

    private final ConcurrentMap<String, CopyOnWriteArrayList<PermissionRule>> rulesBySession = new ConcurrentHashMap<>();
    private final List<PermissionRule> legacyMirror;

    RuntimePermissionRuleStore() {
        this(null);
    }

    RuntimePermissionRuleStore(List<PermissionRule> legacyMirror) {
        this.legacyMirror = legacyMirror;
    }

    void add(String sessionId, PermissionRule rule) {
        if (rule == null) {
            return;
        }
        rulesBySession
            .computeIfAbsent(normalizeSessionId(sessionId), ignored -> new CopyOnWriteArrayList<>())
            .add(rule);
        if (legacyMirror != null) {
            legacyMirror.add(rule);
        }
    }

    List<PermissionRule> rulesFor(String sessionId) {
        List<PermissionRule> rules = rulesBySession.get(normalizeSessionId(sessionId));
        return rules == null ? List.of() : List.copyOf(rules);
    }

    private String normalizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return UNKNOWN_SESSION_ID;
        }
        return Objects.requireNonNull(sessionId);
    }
}
