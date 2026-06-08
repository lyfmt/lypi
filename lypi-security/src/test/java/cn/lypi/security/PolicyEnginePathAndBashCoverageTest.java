package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyEnginePathAndBashCoverageTest {
    private final BashRiskAnalyzer bashRiskAnalyzer = new DefaultBashRiskAnalyzer();

    @Test
    void classifiesBashPipelineSubshellRedirectAndEnvironmentWrappedCommands() {
        BashRiskAnalysis pipeline = bashRiskAnalyzer.analyze("cat README.md | rg TODO | wc -l");
        BashRiskAnalysis subshell = bashRiskAnalyzer.analyze("(rm -rf target)");
        BashRiskAnalysis redirect = bashRiskAnalyzer.analyze("FOO=bar echo hello > notes/output.txt");
        BashRiskAnalysis envWrapped = bashRiskAnalyzer.analyze("env -i PATH=/usr/bin timeout 5 git status --short");
        BashRiskAnalysis envSplit = bashRiskAnalyzer.analyze("env -S 'rm -rf target'");

        assertThat(pipeline.parsedCommands()).containsExactly("cat README.md", "rg TODO", "wc -l");
        assertThat(pipeline.riskLevel()).isEqualTo(BashRiskLevel.LOW);
        assertThat(pipeline.staticallyKnown()).isTrue();
        assertThat(subshell.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(subshell.staticallyKnown()).isFalse();
        assertThat(redirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
        assertThat(redirect.riskLevel()).isEqualTo(BashRiskLevel.MEDIUM);
        assertThat(envWrapped.parsedCommands()).containsExactly("git status");
        assertThat(envWrapped.riskLevel()).isEqualTo(BashRiskLevel.LOW);
        assertThat(envWrapped.staticallyKnown()).isTrue();
        assertThat(envSplit.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(envSplit.staticallyKnown()).isFalse();
    }

    @Test
    void policyDeniesTraversalAndSymlinkEscapesForReadAndWriteTools(@TempDir Path tempDir) throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        Files.createSymbolicLink(workspace.resolve("outside-link"), outside);
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.ALLOW, "read", "*", "allow reads"),
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.ALLOW, "write", "*", "allow writes")
        ));
        ToolUseContext context = context(PermissionMode.BYPASS, workspace, Map.of());

        PermissionDecision readTraversal = engine.decide(
            request("read", Map.of("path", "../outside/secret.txt")),
            context
        );
        PermissionDecision writeTraversal = engine.decide(
            request("write", Map.of("path", "../outside/secret.txt", "content", "x")),
            context
        );
        PermissionDecision readSymlink = engine.decide(
            request("read", Map.of("path", "outside-link/secret.txt")),
            context
        );
        PermissionDecision writeSymlink = engine.decide(
            request("write", Map.of("path", "outside-link/secret.txt", "content", "x")),
            context
        );

        assertDeniedByPathSafety(readTraversal);
        assertDeniedByPathSafety(writeTraversal);
        assertDeniedByPathSafety(readSymlink);
        assertDeniedByPathSafety(writeSymlink);
    }

    @Test
    void sessionDenyOverridesGlobalAllowAndGlobalDenyOverridesSessionAllow() {
        DefaultPolicyEngine globalAllowEngine = new DefaultPolicyEngine(List.of(
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.ALLOW, "bash", "git push *", "project allows push")
        ));
        PermissionRule sessionDeny = rule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.DENY,
            "bash",
            "git push *",
            "session denies push"
        );

        PermissionDecision sessionDenyDecision = globalAllowEngine.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(PermissionMode.BYPASS, Path.of("/workspace"), Map.of("permissionRules", List.of(sessionDeny)))
        );

        DefaultPolicyEngine globalDenyEngine = new DefaultPolicyEngine(List.of(
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.DENY, "bash", "git push *", "project denies push")
        ));
        PermissionRule sessionAllow = rule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ALLOW,
            "bash",
            "git push *",
            "session allows push"
        );

        PermissionDecision globalDenyDecision = globalDenyEngine.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(PermissionMode.BYPASS, Path.of("/workspace"), Map.of("permissionRules", List.of(sessionAllow)))
        );

        assertThat(sessionDenyDecision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(sessionDenyDecision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(sessionDenyDecision.message()).contains("session denies push");
        assertThat(globalDenyDecision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(globalDenyDecision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(globalDenyDecision.message()).contains("project denies push");
    }

    @Test
    void sessionAskOverridesGlobalAllowButCannotOverrideHardPathDeny() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.ALLOW, "write", "*", "project allows writes")
        ));
        PermissionRule sessionAsk = rule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ASK,
            "write",
            "*",
            "session requires write confirmation"
        );
        ToolUseContext context = context(
            PermissionMode.BYPASS,
            Path.of("/workspace"),
            Map.of("permissionRules", List.of(sessionAsk))
        );

        PermissionDecision normalWrite = engine.decide(
            request("write", Map.of("path", "notes.txt", "content", "ok")),
            context
        );
        PermissionDecision traversalWrite = engine.decide(
            request("write", Map.of("path", "../outside.txt", "content", "no")),
            context
        );

        assertThat(normalWrite.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(normalWrite.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(normalWrite.message()).contains("session requires write confirmation");
        assertThat(traversalWrite.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(traversalWrite.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    @Test
    void sessionAllowOverridesGlobalAskForSameStaticallyKnownCommand() {
        DefaultPolicyEngine engine = new DefaultPolicyEngine(List.of(
            rule(PermissionRuleSource.PROJECT, PermissionBehavior.ASK, "bash", "git push *", "project confirms pushes")
        ));
        PermissionRule sessionAllow = rule(
            PermissionRuleSource.SESSION,
            PermissionBehavior.ALLOW,
            "bash",
            "git push origin feature/security",
            "session allowed this push"
        );

        PermissionDecision decision = engine.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(PermissionMode.BYPASS, Path.of("/workspace"), Map.of("permissionRules", List.of(sessionAllow)))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
        assertThat(decision.message()).contains("session allowed this push");
    }

    private void assertDeniedByPathSafety(PermissionDecision decision) {
        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.PATH_SAFETY);
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode mode, Path cwd, Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extraMetadata);
        metadata.put("permissionMode", mode);
        return new ToolUseContext("ses_1", "msg_1", cwd, metadata);
    }

    private PermissionRule rule(
        PermissionRuleSource source,
        PermissionBehavior behavior,
        String toolName,
        String pattern,
        String reason
    ) {
        return new PermissionRule(source, behavior, new PermissionRuleValue(toolName, pattern), reason);
    }
}
