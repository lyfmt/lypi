package cn.lypi.security;

import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按 Codex 风格顺序执行权限决策阶段。
 */
public final class PermissionDecisionPipeline {
    private static final String METADATA_AGENT_MODE = "agentMode";
    private static final String METADATA_PERMISSION_RUNTIME_STATE = "permissionRuntimeState";
    private static final String METADATA_PERMISSION_MODE = "permissionMode";
    private static final String METADATA_PERMISSION_RULES = "permissionRules";
    private static final String METADATA_STRICT_AUTO_REVIEW = "strictAutoReview";
    private static final String INPUT_SANDBOX_PERMISSIONS = "sandboxPermissions";
    private static final String REQUEST_PERMISSIONS_TOOL = "request_permissions";

    private final List<PermissionRule> rules;
    private final BashRiskAnalyzer bashRiskAnalyzer;
    private final PathSafetyChecker pathSafetyChecker;
    private final BashRuleMatcher bashRuleMatcher;
    private final BashPrefixPolicy bashPrefixPolicy;

    public PermissionDecisionPipeline() {
        this(List.of(), new DefaultBashRiskAnalyzer());
    }

    public PermissionDecisionPipeline(List<PermissionRule> rules) {
        this(rules, new DefaultBashRiskAnalyzer());
    }

    public PermissionDecisionPipeline(List<PermissionRule> rules, BashRiskAnalyzer bashRiskAnalyzer) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.bashRiskAnalyzer = bashRiskAnalyzer;
        this.pathSafetyChecker = new PathSafetyChecker();
        BashCommandNormalizer normalizer = new BashCommandNormalizer();
        this.bashRuleMatcher = new BashRuleMatcher(normalizer);
        this.bashPrefixPolicy = new BashPrefixPolicy(normalizer);
    }

    /**
     * 根据工具请求和上下文返回权限决策。
     *
     * NOTE: 显式 DENY、hard safety 和未知 Bash 风险不能被宽松模式绕过。
     */
    public PermissionDecision decide(ToolUseRequest request, ToolUseContext context) {
        BashRiskAnalysis bashRisk = bashRisk(request);
        List<PermissionRule> effectiveRules = effectiveRules(context);
        Optional<PermissionDecision> explicitDeny = explicitDecision(
            request,
            effectiveRules,
            PermissionBehavior.DENY,
            bashRisk
        );
        if (explicitDeny.isPresent()) {
            return explicitDeny.get();
        }

        Optional<PermissionDecision> planModeDecision = planModeDecision(request, context);
        if (planModeDecision.isPresent()) {
            return planModeDecision.get();
        }

        Optional<PermissionDecision> pathSafety = pathSafetyDecision(request, context);
        if (pathSafety.isPresent()) {
            return hardSafety(pathSafety.get());
        }

        Optional<PermissionDecision> legacyWorkspaceBoundary = legacyWorkspaceBoundaryDecision(request, context);
        if (legacyWorkspaceBoundary.isPresent()) {
            return legacyWorkspaceBoundary.get();
        }

        Optional<PermissionDecision> bashRedirectDecision = bashRedirectDecision(request, context, bashRisk);
        if (bashRedirectDecision.isPresent()) {
            return bashRedirectDecision.get();
        }

        Optional<PermissionDecision> prefixAllow = prefixAllowDecision(request, effectiveRules, bashRisk);
        if (prefixAllow.isPresent()) {
            return strictAutoReviewDecision(context, prefixAllow.get());
        }

        Optional<PermissionDecision> explicitAllow = explicitAllowDecision(request, effectiveRules, bashRisk);
        if (explicitAllow.isPresent()) {
            return strictAutoReviewDecision(context, explicitAllow.get());
        }

        Optional<PermissionDecision> bashRiskDecision = bashRiskDecision(request, permissionMode(context), bashRisk);
        if (bashRiskDecision.isPresent()) {
            return bashRiskDecision.get();
        }

        Optional<PermissionDecision> explicitAsk = explicitDecision(
            request,
            effectiveRules,
            PermissionBehavior.ASK,
            bashRisk
        );
        if (explicitAsk.isPresent()) {
            return explicitAsk.get();
        }

        return strictAutoReviewDecision(context, decision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "当前权限模式允许工具调用。",
            Map.of()
        ));
    }

    private Optional<PermissionDecision> explicitDecision(
        ToolUseRequest request,
        List<PermissionRule> effectiveRules,
        PermissionBehavior behavior,
        BashRiskAnalysis bashRisk
    ) {
        for (PermissionRule rule : effectiveRules) {
            if (rule.behavior() == behavior && matches(rule, request, bashRisk)) {
                return Optional.of(decision(
                    behavior,
                    PermissionDecisionReason.EXPLICIT_RULE,
                    "命中权限规则: " + rule.reason(),
                    Map.of("rule", rule)
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<PermissionDecision> prefixAllowDecision(
        ToolUseRequest request,
        List<PermissionRule> effectiveRules,
        BashRiskAnalysis bashRisk
    ) {
        if (!isBashTool(request.toolName()) || bashRisk == null) {
            return Optional.empty();
        }
        for (PermissionRule rule : effectiveRules) {
            if (rule.behavior() == PermissionBehavior.ALLOW && bashPrefixPolicy.matchesPrefixRule(rule, bashRisk)) {
                return Optional.of(decision(
                    PermissionBehavior.ALLOW,
                    PermissionDecisionReason.EXPLICIT_RULE,
                    "命中 Bash prefix 规则: " + rule.reason(),
                    Map.of("rule", rule)
                ));
            }
        }
        return Optional.empty();
    }

    private Optional<PermissionDecision> explicitAllowDecision(
        ToolUseRequest request,
        List<PermissionRule> effectiveRules,
        BashRiskAnalysis bashRisk
    ) {
        Optional<PermissionDecision> explicitAllow = explicitDecision(
            request,
            effectiveRules,
            PermissionBehavior.ALLOW,
            bashRisk
        );
        if (explicitAllow.isEmpty() || !isBashTool(request.toolName())) {
            return explicitAllow;
        }
        if (bashRisk == null || !bashRisk.staticallyKnown() || bashRisk.riskLevel() == BashRiskLevel.UNKNOWN
            || bashRisk.riskLevel() == BashRiskLevel.DESTRUCTIVE) {
            return Optional.empty();
        }
        return explicitAllow;
    }

    private Optional<PermissionDecision> pathSafetyDecision(ToolUseRequest request, ToolUseContext context) {
        return pathSafetyChecker.check(request, context);
    }

    private Optional<PermissionDecision> legacyWorkspaceBoundaryDecision(ToolUseRequest request, ToolUseContext context) {
        // NOTE: Task 6 moves profile boundary out of PathSafetyChecker. The legacy path boundary remains
        // here until the profile projection is wired into this pipeline.
        for (String fieldName : List.of("path", "filePath", "targetPath", "sourcePath", "destinationPath", "cwd")) {
            Object rawPath = request.input().get(fieldName);
            if (rawPath == null) {
                continue;
            }
            Optional<PermissionDecision> decision = legacyWorkspacePathDecision(
                fieldName,
                rawPath.toString(),
                context.cwd(),
                context.cwd()
            );
            if (decision.isPresent()) {
                return decision;
            }
        }
        return Optional.empty();
    }

    private Optional<PermissionDecision> bashRedirectDecision(
        ToolUseRequest request,
        ToolUseContext context,
        BashRiskAnalysis bashRisk
    ) {
        if (!isBashTool(request.toolName()) || bashRisk == null || bashRisk.redirectTargets().isEmpty()) {
            return Optional.empty();
        }
        Path redirectBase = bashCwd(request, context);
        for (Path redirectTarget : bashRisk.redirectTargets()) {
            Optional<PermissionDecision> boundaryDecision = legacyWorkspacePathDecision(
                "bashRedirectTarget",
                redirectTarget.toString(),
                redirectBase,
                context.cwd()
            );
            if (boundaryDecision.isPresent()) {
                return Optional.of(withBashRisk(boundaryDecision.get(), bashRisk));
            }
            Optional<PermissionDecision> pathDecision = pathSafetyChecker.checkPathInsideWorkspace(
                "bashRedirectTarget",
                redirectTarget.toString(),
                context,
                redirectBase
            );
            if (pathDecision.isPresent()) {
                return Optional.of(withBashRisk(hardSafety(pathDecision.get()), bashRisk));
            }
        }
        return Optional.empty();
    }

    private Optional<PermissionDecision> legacyWorkspacePathDecision(
        String fieldName,
        String rawPath,
        Path baseCwd,
        Path workspaceCwd
    ) {
        Path workspace = workspaceCwd.toAbsolutePath().normalize();
        Path target = baseCwd.toAbsolutePath().normalize().resolve(rawPath).normalize();
        if (!target.startsWith(workspace)) {
            return Optional.of(new PermissionDecision(
                PermissionBehavior.DENY,
                PermissionDecisionReason.PATH_SAFETY,
                "工具路径越过当前工作目录: " + rawPath,
                Optional.<PermissionUpdate>empty(),
                Map.of(
                    "pathField", fieldName,
                    "path", rawPath,
                    "normalizedPath", target.toString()
                )
            ));
        }
        return Optional.empty();
    }

    private Optional<PermissionDecision> planModeDecision(ToolUseRequest request, ToolUseContext context) {
        if (agentMode(context) != AgentMode.PLAN) {
            return Optional.empty();
        }
        if (requiresSandboxEscalation(request)) {
            return Optional.of(decision(
                PermissionBehavior.DENY,
                PermissionDecisionReason.SANDBOX_POLICY,
                "AgentMode.PLAN 禁止沙箱提权执行。",
                Map.of("sandboxPermissions", "requireEscalated")
            ));
        }
        if (REQUEST_PERMISSIONS_TOOL.equals(request.toolName())) {
            return Optional.of(decision(
                PermissionBehavior.DENY,
                PermissionDecisionReason.SANDBOX_POLICY,
                "AgentMode.PLAN 禁止请求权限升级。",
                Map.of("toolName", request.toolName())
            ));
        }
        return Optional.empty();
    }

    private PermissionDecision strictAutoReviewDecision(ToolUseContext context, PermissionDecision decision) {
        if (!strictAutoReview(context) || decision.behavior() != PermissionBehavior.ALLOW) {
            return decision;
        }
        return decision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.SANDBOX_POLICY,
            "strictAutoReview 要求本轮后续命令先进入人工 review。",
            Map.of(METADATA_STRICT_AUTO_REVIEW, true)
        );
    }

    private Optional<PermissionDecision> bashRiskDecision(
        ToolUseRequest request,
        PermissionMode mode,
        BashRiskAnalysis bashRisk
    ) {
        // NOTE: BYPASS 仍不能越过未知 Bash；DEFAULT_EXECUTE 对非低风险 Bash 更保守。
        if (!isBashTool(request.toolName())) {
            return Optional.empty();
        }
        if (!bashRisk.staticallyKnown() || bashRisk.riskLevel() == BashRiskLevel.UNKNOWN) {
            return Optional.of(decisionWithSuggestedUpdate(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "Bash 命令无法静态确认风险，需要用户确认。",
                Map.of("bashRisk", bashRisk),
                Optional.empty()
            ));
        }
        if (bashRisk.riskLevel() == BashRiskLevel.DESTRUCTIVE) {
            return Optional.of(decisionWithSuggestedUpdate(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "Bash 命令包含破坏性操作，需要用户确认。",
                Map.of("bashRisk", bashRisk),
                Optional.empty()
            ));
        }
        if (mode == PermissionMode.DEFAULT_EXECUTE && bashRisk.riskLevel() != BashRiskLevel.LOW) {
            return Optional.of(decisionWithSuggestedUpdate(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "默认执行模式下 Bash 写入、网络或远端变更需要用户确认。",
                Map.of("bashRisk", bashRisk),
                bashPrefixPolicy.suggestedUpdate(request, bashRisk)
            ));
        }
        return Optional.empty();
    }

    private BashRiskAnalysis bashRisk(ToolUseRequest request) {
        if (!isBashTool(request.toolName())) {
            return null;
        }
        return bashRiskAnalyzer.analyze(commandInput(request));
    }

    private List<PermissionRule> effectiveRules(ToolUseContext context) {
        List<PermissionRule> effectiveRules = new ArrayList<>(rules);
        Object metadataRules = context.metadata().get(METADATA_PERMISSION_RULES);
        if (metadataRules instanceof Iterable<?> iterable) {
            for (Object candidate : iterable) {
                if (candidate instanceof PermissionRule rule) {
                    effectiveRules.add(rule);
                }
            }
        }
        return List.copyOf(effectiveRules);
    }

    private boolean matches(PermissionRule rule, ToolUseRequest request, BashRiskAnalysis bashRisk) {
        if (isBashTool(request.toolName())) {
            if (rule.behavior() == PermissionBehavior.ALLOW && bashPrefixPolicy.isPrefixRule(rule)) {
                return false;
            }
            return bashRuleMatcher.matches(rule, request, bashRisk);
        }
        String ruleToolName = rule.value().toolName();
        if (ruleToolName != null && !ruleToolName.equals("*") && !ruleToolName.equals(request.toolName())) {
            return false;
        }
        String pattern = rule.value().pattern();
        if (pattern == null || pattern.isBlank() || pattern.equals("*")) {
            return true;
        }
        return wildcard(pattern).matcher(request.input().toString()).matches();
    }

    private Pattern wildcard(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char character : pattern.toCharArray()) {
            if (character == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private Path bashCwd(ToolUseRequest request, ToolUseContext context) {
        Object rawCwd = request.input().get("cwd");
        if (rawCwd == null || rawCwd.toString().isBlank()) {
            return context.cwd();
        }
        return context.cwd().resolve(rawCwd.toString()).normalize();
    }

    private AgentMode agentMode(ToolUseContext context) {
        Object value = context.metadata().get(METADATA_AGENT_MODE);
        if (value instanceof AgentMode agentMode) {
            return agentMode;
        }
        if (value instanceof String agentMode) {
            return AgentMode.valueOf(agentMode);
        }
        return AgentMode.EXECUTE;
    }

    private PermissionMode permissionMode(ToolUseContext context) {
        Object runtimeStateValue = context.metadata().get(METADATA_PERMISSION_RUNTIME_STATE);
        if (runtimeStateValue instanceof PermissionRuntimeState runtimeState) {
            return runtimeState.legacyPermissionMode();
        }
        Object value = context.metadata().get(METADATA_PERMISSION_MODE);
        if (value instanceof PermissionMode permissionMode) {
            return permissionMode;
        }
        if (value instanceof String permissionMode) {
            return PermissionMode.valueOf(permissionMode);
        }
        return PermissionMode.DEFAULT_EXECUTE;
    }

    private boolean strictAutoReview(ToolUseContext context) {
        Object value = context.metadata().get(METADATA_STRICT_AUTO_REVIEW);
        if (value instanceof Boolean strictAutoReview) {
            return strictAutoReview;
        }
        return value instanceof String strictAutoReview && Boolean.parseBoolean(strictAutoReview);
    }

    private boolean requiresSandboxEscalation(ToolUseRequest request) {
        return "requireEscalated".equals(stringInput(request.input(), INPUT_SANDBOX_PERMISSIONS));
    }

    private boolean isBashTool(String toolName) {
        return "bash".equals(toolName);
    }

    private String commandInput(ToolUseRequest request) {
        Object command = request.input().get("command");
        if (command == null) {
            command = request.input().get("cmd");
        }
        return command == null ? "" : command.toString();
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString().trim();
    }

    private PermissionDecision hardSafety(PermissionDecision decision) {
        return new PermissionDecision(
            decision.behavior(),
            PermissionDecisionReason.HARD_SAFETY,
            decision.message(),
            decision.suggestedUpdate(),
            decision.metadata()
        );
    }

    private PermissionDecision decision(
        PermissionBehavior behavior,
        PermissionDecisionReason reason,
        String message,
        Map<String, Object> metadata
    ) {
        return new PermissionDecision(
            behavior,
            reason,
            message,
            Optional.<PermissionUpdate>empty(),
            Map.copyOf(metadata)
        );
    }

    private PermissionDecision decisionWithSuggestedUpdate(
        PermissionBehavior behavior,
        PermissionDecisionReason reason,
        String message,
        Map<String, Object> metadata,
        Optional<PermissionUpdate> suggestedUpdate
    ) {
        return new PermissionDecision(
            behavior,
            reason,
            message,
            suggestedUpdate == null ? Optional.empty() : suggestedUpdate,
            Map.copyOf(metadata)
        );
    }

    private PermissionDecision withBashRisk(PermissionDecision decision, BashRiskAnalysis bashRisk) {
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(decision.metadata());
        metadata.put("bashRisk", bashRisk);
        return new PermissionDecision(
            decision.behavior(),
            decision.reason(),
            decision.message(),
            decision.suggestedUpdate(),
            Map.copyOf(metadata)
        );
    }
}
