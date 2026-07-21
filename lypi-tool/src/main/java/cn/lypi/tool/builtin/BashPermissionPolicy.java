package cn.lypi.tool.builtin;

import cn.lypi.contracts.runtime.SandboxRuntimePolicy;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.tool.shell.SandboxPolicyResolver;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 判定 Bash 工具自身的权限默认值。
 *
 * NOTE: 全局安全运行时仍负责 Bash 风险分析、路径硬安全线和显式规则。
 */
final class BashPermissionPolicy {
    private final SandboxPolicyResolver sandboxPolicyResolver;

    BashPermissionPolicy(SandboxPolicyResolver sandboxPolicyResolver) {
        this.sandboxPolicyResolver = Objects.requireNonNull(sandboxPolicyResolver, "sandboxPolicyResolver must not be null");
    }

    PermissionDecision decide(
        Map<String, Object> input,
        ToolUseContext context,
        Path cwd,
        PermissionRuntimeState permissionRuntimeState
    ) {
        SandboxRuntimePolicy sandboxPolicy = sandboxPolicyResolver.resolve(
            context.cwd(),
            cwd,
            permissionRuntimeState
        );
        if (sandboxPolicy.autoAllowBashIfSandboxed() && sandboxPolicy.failIfUnavailable()) {
            return new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "沙盒策略允许 Bash 工具级直通。",
                Optional.<PermissionUpdate>empty(),
                Map.of(
                    "tool", "bash",
                    "sandboxed", true,
                    "failIfUnavailable", true,
                    "autoAllowBashIfSandboxed", true
                )
            );
        }
        return ask(input);
    }

    PermissionDecision ask(Map<String, Object> input) {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.BASH_RISK,
            "执行 shell 命令需要确认。",
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", "bash", "command", input.getOrDefault("command", "").toString())
        );
    }
}
