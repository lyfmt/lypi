package cn.lypi.resource;

import cn.lypi.contracts.security.PermissionRuntimeState;
import java.util.List;

/**
 * 渲染模型可见的权限运行态说明。
 */
final class PermissionPromptSection implements SystemPromptSection {
    private static final String MODE_DESCRIPTION =
        "Follow the permission and sandbox guidance below for tool calls.";
    private static final String SANDBOX_GUIDANCE =
        "Treat Bash as running inside the active sandbox by default. "
            + "Paths may be read-only or not visible, and network access may be unavailable. "
            + "If the task genuinely requires access beyond the sandbox, issue a new Bash request with "
            + "sandboxPermissions=requireEscalated and a user-facing justification. "
            + "The runtime does not request escalation or retry outside the sandbox automatically.";

    private final PermissionRuntimeState runtimeState;

    PermissionPromptSection(PermissionRuntimeState runtimeState) {
        this.runtimeState = runtimeState;
    }

    @Override
    public void appendTo(StringBuilder content, List<String> sourceNames) {
        if (runtimeState == null) {
            return;
        }
        sourceNames.add("permission-runtime-state");
        content.append("## Permissions\n");
        content.append("- Current permission mode: ")
            .append(runtimeState.mode())
            .append(". ")
            .append(MODE_DESCRIPTION)
            .append("\n");
        content.append("- Current approval policy metadata: ")
            .append(runtimeState.approvalPolicy().mode())
            .append(". The permission mode above is the final review route.\n");
        content.append("- Current sandbox profile: active sandbox profile: ")
            .append(runtimeState.activePermissionProfile().id())
            .append(". Do not infer filesystem or network access from legacy permissionMode names.\n");
        content.append("- Use `request_permissions` to request additional filesystem or network permissions for the current turn or session. ")
            .append("Set `strictAutoReview` when the following command should still be reviewed after permission approval.\n");
        content.append("- ").append(SANDBOX_GUIDANCE).append("\n");
        content.append("- For `bash`, use `sandboxPermissions=withAdditionalPermissions` only after `request_permissions` has approved the matching additional permissions.\n\n");
    }
}
