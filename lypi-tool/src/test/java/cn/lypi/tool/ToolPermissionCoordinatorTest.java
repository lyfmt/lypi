package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ToolPermissionCoordinatorTest {
    @Test
    void readOnlyToolsSkipSecurityToolGateAndReviewerInEveryMode() {
        for (PermissionMode mode : PermissionMode.values()) {
            AtomicInteger securityCalls = new AtomicInteger();
            AtomicInteger permissionCalls = new AtomicInteger();
            AtomicInteger gateCalls = new AtomicInteger();
            AtomicInteger reviewerCalls = new AtomicInteger();
            ToolPermissionCoordinator coordinator = coordinator(
                (request, context) -> {
                    securityCalls.incrementAndGet();
                    return TestTools.decision(PermissionBehavior.DENY, "security deny");
                },
                (request, tool, context, decision) -> {
                    gateCalls.incrementAndGet();
                    return PermissionGateResult.deny("gate deny");
                },
                (request, tool, context, snapshot, decision) -> {
                    reviewerCalls.incrementAndGet();
                    return PermissionGateResult.deny("reviewer deny");
                }
            );

            ToolPermissionCoordinator.Result result = coordinator.authorize(
                request("read", Map.of()),
                TestTools.permissionProbeTool("read", true, PermissionBehavior.DENY, permissionCalls),
                Map.of(),
                context(mode)
            );

            assertTrue(result.allowed(), mode.name());
            assertFalse(result.approvedDuringCurrentCall(), mode.name());
            assertEquals(0, securityCalls.get(), mode.name());
            assertEquals(0, permissionCalls.get(), mode.name());
            assertEquals(0, gateCalls.get(), mode.name());
            assertEquals(0, reviewerCalls.get(), mode.name());
        }
    }

    @Test
    void askRoutesNonAllowEffectiveDecisionsOnlyToUserGate() {
        assertAskRoute(PermissionBehavior.DENY, PermissionBehavior.ALLOW);
        assertAskRoute(PermissionBehavior.ASK, PermissionBehavior.ALLOW);
        assertAskRoute(PermissionBehavior.ALLOW, PermissionBehavior.DENY);
        assertAskRoute(PermissionBehavior.ALLOW, PermissionBehavior.ASK);
    }

    @Test
    void autoRoutesNonAllowEffectiveDecisionsOnlyToModelReviewer() {
        assertAutoRoute(PermissionBehavior.DENY, PermissionBehavior.ALLOW);
        assertAutoRoute(PermissionBehavior.ASK, PermissionBehavior.ALLOW);
        assertAutoRoute(PermissionBehavior.ALLOW, PermissionBehavior.DENY);
        assertAutoRoute(PermissionBehavior.ALLOW, PermissionBehavior.ASK);
    }

    @Test
    void askDirectlyAllowsEffectiveAllowWithoutCallingPermissionGate() {
        assertDirectAllowWithoutReview(
            PermissionMode.ASK,
            TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            PermissionBehavior.ALLOW
        );
    }

    @Test
    void autoDirectlyAllowsEffectiveAllowWithoutCallingPermissionReviewer() {
        assertDirectAllowWithoutReview(
            PermissionMode.AUTO,
            TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            PermissionBehavior.ALLOW
        );
    }

    @Test
    void explicitRuleAllowExecutesWithoutReviewEvenWhenToolAsks() {
        assertDirectAllowWithoutReview(
            PermissionMode.ASK,
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.EXPLICIT_RULE,
                "prefix allow",
                Optional.empty(),
                Map.of()
            ),
            PermissionBehavior.ASK
        );
    }

    @Test
    void bypassSkipsSecurityToolGateAndReviewerForNonReadOnlyTools() {
        AtomicInteger securityCalls = new AtomicInteger();
        AtomicInteger permissionCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> {
                securityCalls.incrementAndGet();
                return TestTools.decision(PermissionBehavior.DENY, "security deny");
            },
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("gate deny");
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                return PermissionGateResult.deny("reviewer deny");
            }
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of()),
            TestTools.permissionProbeTool("write", false, PermissionBehavior.DENY, permissionCalls),
            Map.of(),
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertFalse(result.approvedDuringCurrentCall());
        assertEquals(0, securityCalls.get());
        assertEquals(0, permissionCalls.get());
        assertEquals(0, gateCalls.get());
        assertEquals(0, reviewerCalls.get());
    }

    @Test
    void bypassApprovesInlineAdditionalPermissionsWithoutReview() {
        AdditionalPermissionProfile permissions = additionalWrite("/workspace/cache");
        AtomicInteger securityCalls = new AtomicInteger();
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> {
                securityCalls.incrementAndGet();
                return TestTools.decision(PermissionBehavior.DENY, "security deny");
            },
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("gate deny");
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                return PermissionGateResult.deny("reviewer deny");
            }
        );
        Map<String, Object> input = Map.of(
            "command", "touch cache/out",
            "sandboxPermissions", "withAdditionalPermissions",
            "additionalPermissions", permissions
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.DENY),
            input,
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertFalse(result.approvedDuringCurrentCall());
        assertEquals(Optional.of(permissions), result.approvedAdditionalPermissions());
        assertEquals(0, securityCalls.get());
        assertEquals(0, gateCalls.get());
        assertEquals(0, reviewerCalls.get());
    }

    @Test
    void autoFailsClosedWhenReviewerDeniesOrThrowsForAskDecision() {
        Tool<Map<String, Object>, String> tool = TestTools.permission("write", PermissionBehavior.ALLOW);
        ToolPermissionCoordinator denied = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, candidate, context, decision) -> PermissionGateResult.allow(),
            (request, candidate, context, snapshot, decision) -> PermissionGateResult.deny("model deny")
        );
        ToolPermissionCoordinator failed = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, candidate, context, decision) -> PermissionGateResult.allow(),
            (request, candidate, context, snapshot, decision) -> {
                throw new IllegalStateException("provider unavailable");
            }
        );

        ToolPermissionCoordinator.Result deniedResult = denied.authorize(
            request("write", Map.of()), tool, Map.of(), context(PermissionMode.AUTO)
        );
        ToolPermissionCoordinator.Result failedResult = failed.authorize(
            request("write", Map.of()), tool, Map.of(), context(PermissionMode.AUTO)
        );

        assertFalse(deniedResult.allowed());
        assertEquals("model deny", deniedResult.gateResult().message().orElseThrow());
        assertFalse(failedResult.allowed());
        assertTrue(failedResult.gateResult().message().orElseThrow().contains("provider unavailable"));
    }

    @Test
    void allowsToolSpecificAskWhenGateAllows() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Tool<Map<String, Object>, String> tool = TestTools.permission("write", PermissionBehavior.ASK);

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            tool,
            Map.of("text", "ok"),
            context(PermissionMode.ASK)
        );

        assertTrue(result.allowed());
        assertTrue(result.approvedDuringCurrentCall());
        assertEquals(PermissionBehavior.ASK, requestedDecision.get().behavior());
    }

    @Test
    void bypassAllowsWithoutCallingGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("should not ask");
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "ok"),
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertFalse(result.approvedDuringCurrentCall());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void onRequestPolicyStillPromptsForStrictAutoReviewAsk() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.SANDBOX_POLICY,
                "strictAutoReview 要求本轮后续命令先进入人工 review。",
                Optional.empty(),
                Map.of("strictAutoReview", true)
            ),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "ok")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "ok"),
            context(PermissionMode.ASK)
        );

        assertTrue(result.allowed(), () -> result.gateResult().status() + " " + result.gateResult().message().orElse(""));
        assertTrue(result.approvedDuringCurrentCall());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void defaultSandboxBashDelegatesStrictAutoReviewAskUnderOnRequestPolicy() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.SANDBOX_POLICY,
                "strictAutoReview 要求本轮后续命令先进入人工 review。",
                Optional.empty(),
                Map.of("strictAutoReview", true)
            ),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of("command", "pwd");

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(PermissionMode.ASK)
        );

        assertTrue(result.allowed());
        assertTrue(result.approvedDuringCurrentCall());
        assertEquals(1, gateCalls.get());
    }

    @Test
    void eligibleDefaultBashHasNoLegacySandboxDenialOrRetryMetadata() {
        List<BashRiskAnalysis> eligible = List.of(
            bashRisk("pwd", BashRiskLevel.LOW, true),
            bashRisk("touch output.txt", BashRiskLevel.MEDIUM, true),
            bashRisk("curl https://example.com", BashRiskLevel.HIGH, true),
            bashRisk("rm -rf build", BashRiskLevel.DESTRUCTIVE, true)
        );

        for (PermissionMode mode : List.of(PermissionMode.ASK, PermissionMode.AUTO)) {
            for (BashRiskAnalysis risk : eligible) {
                assertDefaultBashDirectAllow(mode, risk);
            }
        }
    }

    @Test
    void reviewOnlyDefaultBashKeepsSecurityAskWithoutLegacyRetryMetadata() {
        List<BashRiskAnalysis> reviewOnly = List.of(
            bashRisk("dd if=a of=b", BashRiskLevel.DESTRUCTIVE, true),
            bashRisk("mkfs /dev/loop0", BashRiskLevel.DESTRUCTIVE, true),
            bashRisk("npm install", BashRiskLevel.HIGH, true),
            bashRisk("pip install package", BashRiskLevel.HIGH, true),
            bashRisk("echo $(id)", BashRiskLevel.UNKNOWN, false)
        );

        for (PermissionMode mode : List.of(PermissionMode.ASK, PermissionMode.AUTO)) {
            for (BashRiskAnalysis risk : reviewOnly) {
                assertDefaultBashReview(mode, risk, PermissionBehavior.ASK);
            }
        }
    }

    @Test
    void askModeRoutesReviewEvenWhenLegacyBehaviorLooksLikeBypass() {
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> PermissionGateResult.deny("should not ask"),
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "id",
            "sandboxPermissions", "requireEscalated",
            "justification", "host inspection"
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithRuntimeState(
                PermissionMode.ASK,
                runtimeStateWithLegacyMode(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), PermissionMode.ASK)
            )
        );

        assertFalse(result.allowed());
        assertEquals(PermissionGateResult.Status.DENY, result.gateResult().status());
    }

    @Test
    void askModeRoutesDefaultBashEvenWhenLegacyBehaviorLooksLikeBypass() {
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.BASH_RISK,
                "默认执行模式下 Bash 写入、网络或远端变更需要用户确认。",
                Optional.empty(),
                Map.of()
            ),
            (request, tool, context, decision) -> PermissionGateResult.deny("should not ask"),
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of("command", "rm -rf build");

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithRuntimeState(
                PermissionMode.ASK,
                runtimeStateWithLegacyMode(PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS), PermissionMode.ASK)
            )
        );

        assertFalse(result.allowed());
        assertEquals(PermissionGateResult.Status.DENY, result.gateResult().status());
    }

    @Test
    void bypassSkipsHardSecurityDecisionAndGate() {
        AtomicInteger gateCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.DENY, "hard deny"),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of("text", "blocked")),
            TestTools.permission("write", PermissionBehavior.ALLOW),
            Map.of("text", "blocked"),
            context(PermissionMode.BYPASS)
        );

        assertTrue(result.allowed());
        assertEquals(0, gateCalls.get());
    }

    @Test
    void appliesAllowedPermissionUpdateToStoreAndRuntimeRules() {
        List<PermissionUpdate> stored = new ArrayList<>();
        List<PermissionRule> runtimeRules = new ArrayList<>();
        PermissionUpdate update = new PermissionUpdate(
            PermissionRuleSource.USER,
            new PermissionRule(
                PermissionRuleSource.USER,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", "prefix:mvn test"),
                "允许 Maven 测试"
            )
        );
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ASK, "security ask"),
            (request, tool, context, decision) -> PermissionGateResult.allow(Optional.of(update)),
            stored::add,
            runtimeRules
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", Map.of("command", "mvn test", "prefix_rule", List.of("mvn", "test"))),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            Map.of("command", "mvn test", "prefix_rule", List.of("mvn", "test")),
            context(PermissionMode.ASK)
        );

        assertTrue(result.allowed());
        assertTrue(result.approvedDuringCurrentCall());
        assertEquals(List.of(update), stored);
        assertEquals(List.of(update.rule()), runtimeRules);
    }

    @Test
    void freshInlineAdditionalPermissionsPromptAndReturnApprovedPayload() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AdditionalPermissionProfile permissions = additionalWrite("/workspace/cache");
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "touch cache/out",
            "sandboxPermissions", "withAdditionalPermissions",
            "additionalPermissions", permissions
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(PermissionMode.ASK)
        );

        assertTrue(result.allowed());
        assertTrue(result.approvedDuringCurrentCall());
        assertEquals(Optional.of(permissions), result.approvedAdditionalPermissions());
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, requestedDecision.get().metadata().get("approvalKind"));
        assertEquals(permissions, requestedDecision.get().metadata().get("additionalPermissions"));
    }

    @Test
    void inlineAdditionalPermissionsMergeWithPreapprovedContextPermissions() {
        AtomicReference<PermissionDecision> requestedDecision = new AtomicReference<>();
        AdditionalPermissionProfile preapproved = additionalWrite("/workspace/cache");
        AdditionalPermissionProfile requested = additionalWrite("/workspace/output");
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(PermissionBehavior.ALLOW, "security allow"),
            (request, tool, context, decision) -> {
                requestedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            PermissionUpdateStore.noop(),
            List.of()
        );
        Map<String, Object> input = Map.of(
            "command", "touch output/out",
            "sandboxPermissions", "withAdditionalPermissions",
            "additionalPermissions", requested
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            contextWithAdditionalPermissions(PermissionMode.ASK, preapproved)
        );

        assertTrue(result.allowed());
        assertTrue(result.approvedDuringCurrentCall());
        AdditionalPermissionProfile merged = result.approvedAdditionalPermissions().orElseThrow();
        assertTrue(merged.fileSystem().orElseThrow().entries().containsAll(List.of(
            preapproved.fileSystem().orElseThrow().entries().getFirst(),
            requested.fileSystem().orElseThrow().entries().getFirst()
        )));
        assertEquals(ApprovalKind.REQUEST_PERMISSIONS, requestedDecision.get().metadata().get("approvalKind"));
        assertEquals(requested, requestedDecision.get().metadata().get("additionalPermissions"));
    }

    private ToolPermissionCoordinator coordinator(
        cn.lypi.contracts.runtime.SecurityRuntimePort security,
        PermissionGate gate,
        PermissionUpdateStore store,
        List<PermissionRule> runtimeRules
    ) {
        return new ToolPermissionCoordinator(
            security,
            gate,
            store,
            runtimeRules,
            new SandboxEscalationPolicy()
        );
    }

    private ToolPermissionCoordinator coordinator(
        cn.lypi.contracts.runtime.SecurityRuntimePort security,
        PermissionGate gate,
        PermissionReviewer reviewer
    ) {
        return new ToolPermissionCoordinator(
            security,
            gate,
            PermissionUpdateStore.noop(),
            List.of(),
            new SandboxEscalationPolicy(),
            reviewer
        );
    }

    private void assertAskRoute(PermissionBehavior securityBehavior, PermissionBehavior toolBehavior) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(securityBehavior, "security " + securityBehavior),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                assertEquals(PermissionBehavior.ASK, decision.behavior());
                return PermissionGateResult.allow();
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                return PermissionGateResult.allow();
            }
        );

        for (ToolUseContext candidate : canonicalAndLegacyContexts(PermissionMode.ASK)) {
            ToolPermissionCoordinator.Result result = coordinator.authorize(
                request("write", Map.of()),
                TestTools.permission("write", toolBehavior),
                Map.of(),
                candidate
            );

            assertTrue(result.allowed());
            assertTrue(result.approvedDuringCurrentCall());
        }
        assertEquals(2, gateCalls.get());
        assertEquals(0, reviewerCalls.get());
    }

    private void assertAutoRoute(PermissionBehavior securityBehavior, PermissionBehavior toolBehavior) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> TestTools.decision(securityBehavior, "security " + securityBehavior),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.allow();
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                assertEquals(PermissionBehavior.ASK, decision.behavior());
                return PermissionGateResult.allow();
            }
        );

        for (ToolUseContext candidate : canonicalAndLegacyContexts(PermissionMode.AUTO)) {
            ToolPermissionCoordinator.Result result = coordinator.authorize(
                request("write", Map.of()),
                TestTools.permission("write", toolBehavior),
                Map.of(),
                candidate
            );

            assertTrue(result.allowed());
            assertTrue(result.approvedDuringCurrentCall());
        }
        assertEquals(0, gateCalls.get());
        assertEquals(2, reviewerCalls.get());
    }

    private void assertDirectAllowWithoutReview(
        PermissionMode mode,
        PermissionDecision securityDecision,
        PermissionBehavior toolBehavior
    ) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> securityDecision,
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("gate must not run");
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                return PermissionGateResult.deny("reviewer must not run");
            }
        );

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("write", Map.of()),
            TestTools.permission("write", toolBehavior),
            Map.of(),
            context(mode)
        );

        assertTrue(result.allowed());
        assertFalse(result.approvedDuringCurrentCall());
        assertEquals(0, gateCalls.get());
        assertEquals(0, reviewerCalls.get());
    }

    private void assertDefaultBashDirectAllow(PermissionMode mode, BashRiskAnalysis risk) {
        AtomicInteger gateCalls = new AtomicInteger();
        AtomicInteger reviewerCalls = new AtomicInteger();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.BASH_RISK,
                "bash risk decision",
                Optional.empty(),
                Map.of("bashRisk", risk)
            ),
            (request, tool, context, decision) -> {
                gateCalls.incrementAndGet();
                return PermissionGateResult.deny("gate must not run");
            },
            (request, tool, context, snapshot, decision) -> {
                reviewerCalls.incrementAndGet();
                return PermissionGateResult.deny("reviewer must not run");
            }
        );
        Map<String, Object> input = Map.of("command", risk.normalizedCommand());

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(mode)
        );

        assertTrue(result.allowed(), mode + ": " + risk.normalizedCommand());
        assertFalse(result.approvedDuringCurrentCall());
        assertEquals(0, gateCalls.get());
        assertEquals(0, reviewerCalls.get());
    }

    private void assertDefaultBashReview(
        PermissionMode mode,
        BashRiskAnalysis risk,
        PermissionBehavior securityBehavior
    ) {
        AtomicReference<PermissionDecision> reviewedDecision = new AtomicReference<>();
        ToolPermissionCoordinator coordinator = coordinator(
            (request, context) -> new PermissionDecision(
                securityBehavior,
                PermissionDecisionReason.BASH_RISK,
                "bash risk decision",
                Optional.empty(),
                Map.of("bashRisk", risk)
            ),
            (request, tool, context, decision) -> {
                reviewedDecision.set(decision);
                return PermissionGateResult.allow();
            },
            (request, tool, context, snapshot, decision) -> {
                reviewedDecision.set(decision);
                return PermissionGateResult.allow();
            }
        );
        Map<String, Object> input = Map.of("command", risk.normalizedCommand());

        ToolPermissionCoordinator.Result result = coordinator.authorize(
            request("bash", input),
            TestTools.permission("bash", PermissionBehavior.ALLOW),
            input,
            context(mode)
        );

        assertTrue(result.allowed(), mode + ": " + risk.normalizedCommand());
        assertTrue(result.approvedDuringCurrentCall());
        assertNotNull(reviewedDecision.get(), mode + ": " + risk.normalizedCommand());
        assertEquals(PermissionBehavior.ASK, reviewedDecision.get().behavior());
        assertEquals(risk, reviewedDecision.get().metadata().get("bashRisk"));
        assertFalse(reviewedDecision.get().metadata().containsKey("sandboxDenied"));
        assertFalse(reviewedDecision.get().metadata().containsKey("retryWith"));
        assertFalse(reviewedDecision.get().metadata().containsKey("retryHint"));
    }

    private BashRiskAnalysis bashRisk(String command, BashRiskLevel riskLevel, boolean staticallyKnown) {
        return new BashRiskAnalysis(
            command,
            List.of(command),
            List.of(),
            riskLevel,
            List.of("test risk"),
            staticallyKnown
        );
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private ToolUseContext context(PermissionMode permissionMode) {
        PermissionRuntimeState runtimeState = PermissionRuntimeState.fromLegacy(permissionMode);
        return new ToolUseContext(
            "ses_1",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                ToolRuntimeContextFactory.METADATA_AGENT_MODE,
                AgentMode.EXECUTE,
                ToolRuntimeContextFactory.METADATA_PERMISSION_MODE,
                permissionMode,
                ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE,
                runtimeState
            )
        );
    }

    private List<ToolUseContext> canonicalAndLegacyContexts(PermissionMode permissionMode) {
        return List.of(context(permissionMode), legacyContext(permissionMode));
    }

    private ToolUseContext legacyContext(PermissionMode permissionMode) {
        return new ToolUseContext(
            "ses_legacy",
            "msg_1",
            Path.of("/workspace"),
            Map.of(
                ToolRuntimeContextFactory.METADATA_AGENT_MODE,
                AgentMode.EXECUTE,
                ToolRuntimeContextFactory.METADATA_PERMISSION_MODE,
                permissionMode
            )
        );
    }

    private ToolUseContext contextWithAdditionalPermissions(
        PermissionMode permissionMode,
        AdditionalPermissionProfile additionalPermissions
    ) {
        ToolUseContext base = context(permissionMode);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.metadata());
        metadata.put("additionalPermissions", additionalPermissions);
        metadata.put("approvedAdditionalPermissions", true);
        return new ToolUseContext(base.sessionId(), base.messageId(), base.cwd(), Map.copyOf(metadata));
    }

    private ToolUseContext contextWithRuntimeState(PermissionMode permissionMode, PermissionRuntimeState runtimeState) {
        ToolUseContext base = context(permissionMode);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(base.metadata());
        metadata.put(ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE, runtimeState);
        metadata.put(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE, permissionMode);
        return new ToolUseContext(base.sessionId(), base.messageId(), base.cwd(), Map.copyOf(metadata));
    }

    private PermissionRuntimeState runtimeStateWithLegacyMode(PermissionRuntimeState source, PermissionMode legacyMode) {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.ON_REQUEST),
            source.activePermissionProfile(),
            source.permissionProfile(),
            source.legacyBehavior(),
            legacyMode
        );
    }

    private AdditionalPermissionProfile additionalWrite(String path) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(
                    FileSystemPath.exactPath(path),
                    FileSystemAccessMode.WRITE
                )
            ))),
            Optional.empty()
        );
    }
}
