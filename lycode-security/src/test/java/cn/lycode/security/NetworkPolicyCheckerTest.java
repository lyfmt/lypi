package cn.lycode.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.security.NetworkPermissionPolicy;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionProfiles;
import org.junit.jupiter.api.Test;

class NetworkPolicyCheckerTest {
    private final NetworkPolicyChecker checker = new NetworkPolicyChecker();

    @Test
    void restrictedNetworkDeniesNetworkUse() {
        PermissionDecision decision = checker.decide(NetworkPermissionPolicy.restricted());

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void enabledNetworkAllowsNetworkUse() {
        PermissionDecision decision = checker.decide(NetworkPermissionPolicy.enabled());

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void dangerFullAccessProfileNetworkIsEnabled() {
        PermissionDecision decision = checker.decide(PermissionProfiles.dangerFullAccess().network());

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
    }

    @Test
    void externalProfileNetworkDefaultsToRestrictedWhenCompiledBuiltin() {
        PermissionProfileConfigCompiler compiler = new PermissionProfileConfigCompiler();

        PermissionDecision decision = checker.decide(compiler.compile(null, ":external").permissionProfile().network());

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
    }
}
