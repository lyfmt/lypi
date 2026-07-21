package cn.lypi.resource;

import cn.lypi.contracts.security.PermissionRuntimeState;
import java.util.List;

/**
 * 渲染模型可见的权限运行态说明。
 */
final class PermissionPromptSection implements SystemPromptSection {
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
            .append(modeDescription())
            .append("\n");
        content.append("- Current approval policy: approval policy: ")
            .append(runtimeState.approvalPolicy().mode())
            .append(". The model may request permissions; the approval policy decides whether a prompt is shown.\n");
        content.append("- Current sandbox profile: active sandbox profile: ")
            .append(runtimeState.activePermissionProfile().id())
            .append(". Do not infer filesystem or network access from legacy permissionMode names.\n");
        content.append("- Use `request_permissions` to request additional filesystem or network permissions for the current turn or session. ")
            .append("Set `strictAutoReview` when the following command should still be reviewed after permission approval.\n");
        content.append("- For `bash`, use `sandboxPermissions=requireEscalated` with a justification when host execution is required.\n");
        content.append("- For `bash`, use `sandboxPermissions=withAdditionalPermissions` only after `request_permissions` has approved the matching additional permissions.\n\n");
    }

    private String modeDescription() {
        return switch (runtimeState.mode()) {
            case ASK -> "Read-only tools run directly; other tool calls require user approval.";
            case AUTO -> "Read-only tools run directly; other tool calls are allowed or denied by an independent model reviewer without prompting the user.";
            case BYPASS -> "Permission review is skipped; actual execution still follows the active sandbox profile and executor constraints.";
        };
    }
}
