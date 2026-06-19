package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.GranularApprovalPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ApprovalPolicyEvaluatorTest {
    private final ApprovalPolicyEvaluator evaluator = new ApprovalPolicyEvaluator();

    @Test
    void onRequestAllowsInteractivePrompt() {
        ApprovalPolicyEvaluator.Decision decision = evaluator.evaluate(
            new ApprovalPolicy(ApprovalMode.ON_REQUEST),
            ApprovalPolicyEvaluator.ApprovalCategory.COMMAND,
            true
        );

        assertThat(decision.outcome()).isEqualTo(ApprovalPolicyEvaluator.Outcome.ALLOW_TO_PROMPT);
    }

    @Test
    void neverDeniesPrompt() {
        ApprovalPolicyEvaluator.Decision decision = evaluator.evaluate(
            new ApprovalPolicy(ApprovalMode.NEVER),
            ApprovalPolicyEvaluator.ApprovalCategory.COMMAND,
            true
        );

        assertThat(decision.outcome()).isEqualTo(ApprovalPolicyEvaluator.Outcome.DENY_WITH_REASON);
        assertThat(decision.reason()).contains("never");
    }

    @Test
    void granularNeverSandboxApprovalDeniesSandboxPrompt() {
        ApprovalPolicy policy = new ApprovalPolicy(
            ApprovalMode.GRANULAR,
            Optional.of(new GranularApprovalPolicy(
                ApprovalMode.NEVER,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST
            ))
        );

        ApprovalPolicyEvaluator.Decision decision = evaluator.evaluate(
            policy,
            ApprovalPolicyEvaluator.ApprovalCategory.SANDBOX,
            true
        );

        assertThat(decision.outcome()).isEqualTo(ApprovalPolicyEvaluator.Outcome.DENY_WITH_REASON);
        assertThat(decision.reason()).contains("sandbox");
    }

    @Test
    void granularNeverRequestPermissionsDeniesRequestPermissionsPrompt() {
        ApprovalPolicy policy = new ApprovalPolicy(
            ApprovalMode.GRANULAR,
            Optional.of(new GranularApprovalPolicy(
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.ON_REQUEST,
                ApprovalMode.NEVER,
                ApprovalMode.ON_REQUEST
            ))
        );

        ApprovalPolicyEvaluator.Decision decision = evaluator.evaluate(
            policy,
            ApprovalPolicyEvaluator.ApprovalCategory.REQUEST_PERMISSIONS,
            true
        );

        assertThat(decision.outcome()).isEqualTo(ApprovalPolicyEvaluator.Outcome.DENY_WITH_REASON);
        assertThat(decision.reason()).contains("request_permissions");
    }

    @Test
    void headlessNonInteractiveDeniesPromptEvenWhenPolicyAllows() {
        ApprovalPolicyEvaluator.Decision decision = evaluator.evaluate(
            new ApprovalPolicy(ApprovalMode.ON_REQUEST),
            ApprovalPolicyEvaluator.ApprovalCategory.COMMAND,
            false
        );

        assertThat(decision.outcome()).isEqualTo(ApprovalPolicyEvaluator.Outcome.DENY_WITH_REASON);
        assertThat(decision.reason()).contains("non-interactive");
    }
}
