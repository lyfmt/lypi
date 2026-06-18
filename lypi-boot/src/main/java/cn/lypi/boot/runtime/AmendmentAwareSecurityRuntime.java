package cn.lypi.boot.runtime;

import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import cn.lypi.security.DefaultPolicyEngine;
import cn.lypi.tool.PermissionAmendmentStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 按会话合并持久化权限修订后委托默认策略引擎。
 */
final class AmendmentAwareSecurityRuntime implements SecurityRuntimePort {
    private final List<PermissionRule> legacyRules;
    private final PermissionAmendmentStore amendmentStore;
    private final DefaultPolicyEngine baseEngine;

    AmendmentAwareSecurityRuntime(List<PermissionRule> legacyRules, PermissionAmendmentStore amendmentStore) {
        this.legacyRules = legacyRules == null ? List.of() : List.copyOf(legacyRules);
        this.amendmentStore = Objects.requireNonNull(amendmentStore, "amendmentStore must not be null");
        this.baseEngine = new DefaultPolicyEngine(this.legacyRules);
    }

    @Override
    public PermissionDecision decide(ToolUseRequest request, ToolUseContext context) {
        List<PermissionRule> amendmentRules = amendmentStore
            .readPermissionUpdates(PermissionGrantScope.SESSION, sessionId(context), turnId(context))
            .stream()
            .map(cn.lypi.contracts.security.PermissionUpdate::rule)
            .filter(Objects::nonNull)
            .toList();
        if (amendmentRules.isEmpty()) {
            return baseEngine.decide(request, context);
        }
        List<PermissionRule> rules = new ArrayList<>(legacyRules);
        rules.addAll(amendmentRules);
        return new DefaultPolicyEngine(rules).decide(request, context);
    }

    private String sessionId(ToolUseContext context) {
        return context == null ? null : context.sessionId();
    }

    private String turnId(ToolUseContext context) {
        if (context == null) {
            return null;
        }
        Object value = context.metadata().get("turnId");
        return value == null ? null : value.toString();
    }
}
