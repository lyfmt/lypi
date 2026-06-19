package cn.lypi.security;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;

/**
 * 默认权限策略引擎。
 *
 * NOTE: 兼容入口只委托到 PermissionDecisionPipeline，具体顺序集中在 pipeline 内维护。
 */
public final class DefaultPolicyEngine implements PolicyEngine {
    private final PermissionDecisionPipeline pipeline;

    public DefaultPolicyEngine() {
        this(List.of(), new DefaultBashRiskAnalyzer());
    }

    public DefaultPolicyEngine(List<PermissionRule> rules) {
        this(rules, new DefaultBashRiskAnalyzer());
    }

    public DefaultPolicyEngine(List<PermissionRule> rules, BashRiskAnalyzer bashRiskAnalyzer) {
        this.pipeline = new PermissionDecisionPipeline(rules, bashRiskAnalyzer);
    }

    /**
     * 根据工具请求和上下文返回权限决策。
     */
    @Override
    public PermissionDecision decide(ToolUseRequest request, ToolUseContext context) {
        return pipeline.decide(request, context);
    }
}
