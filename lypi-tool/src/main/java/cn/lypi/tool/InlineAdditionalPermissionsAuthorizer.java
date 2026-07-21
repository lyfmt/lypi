package cn.lypi.tool;

import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/**
 * 处理 Bash inline additional permissions 审批。
 */
final class InlineAdditionalPermissionsAuthorizer {
    private static final String INPUT_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String INPUT_SANDBOX_PERMISSIONS = "sandboxPermissions";
    private static final String METADATA_ADDITIONAL_PERMISSIONS = "additionalPermissions";
    private static final String METADATA_APPROVED_ADDITIONAL_PERMISSIONS = "approvedAdditionalPermissions";

    private final ApprovalCoordinator approvalCoordinator;

    InlineAdditionalPermissionsAuthorizer(ApprovalCoordinator approvalCoordinator) {
        this.approvalCoordinator = java.util.Objects.requireNonNull(approvalCoordinator, "approvalCoordinator");
    }

    Optional<ToolPermissionCoordinator.Result> authorize(
        ToolUseRequest request,
        Tool<Map<String, Object>, ?> tool,
        ToolUseContext context,
        ContextSnapshot contextSnapshot,
        PermissionDecision securityDecision
    ) {
        if (!appliesTo(request)) {
            return Optional.empty();
        }
        Optional<AdditionalPermissionProfile> preapproved = approvedAdditionalPermissions(context);
        Object rawPermissions = request.input() == null ? null : request.input().get(INPUT_ADDITIONAL_PERMISSIONS);
        if (rawPermissions == null) {
            return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(
                "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
            )));
        }
        AdditionalPermissionProfile additionalPermissions;
        try {
            additionalPermissions = AdditionalPermissionsInputParser.parse(rawPermissions, INPUT_ADDITIONAL_PERMISSIONS);
        } catch (IllegalArgumentException exception) {
            return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(exception.getMessage())));
        }
        if (AdditionalPermissionsInputParser.isEmpty(additionalPermissions)) {
            return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(
                "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
            )));
        }
        String reason = securityDecision == null || securityDecision.message() == null || securityDecision.message().isBlank()
            ? "请求额外权限。"
            : securityDecision.message();
        PermissionGateResult permissionResult = approvalCoordinator.resolveAdditionalPermissions(
            request,
            tool,
            context,
            contextSnapshot,
            reason,
            additionalPermissions
        );
        if (permissionResult.status() != PermissionGateResult.Status.ALLOW) {
            return Optional.of(ToolPermissionCoordinator.Result.disallowed(permissionResult));
        }
        return Optional.of(ToolPermissionCoordinator.Result.allowed(
            permissionResult,
            mergeAdditionalPermissions(preapproved.orElse(AdditionalPermissionProfile.empty()), additionalPermissions)
        ));
    }

    Optional<ToolPermissionCoordinator.Result> authorizeBypass(
        ToolUseRequest request,
        ToolUseContext context
    ) {
        if (!appliesTo(request)) {
            return Optional.empty();
        }
        Object rawPermissions = request.input() == null ? null : request.input().get(INPUT_ADDITIONAL_PERMISSIONS);
        if (rawPermissions == null) {
            return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(
                "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
            )));
        }
        try {
            AdditionalPermissionProfile additionalPermissions = AdditionalPermissionsInputParser.parse(
                rawPermissions,
                INPUT_ADDITIONAL_PERMISSIONS
            );
            if (AdditionalPermissionsInputParser.isEmpty(additionalPermissions)) {
                return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(
                    "sandboxPermissions=withAdditionalPermissions 时 additionalPermissions 不能为空。"
                )));
            }
            AdditionalPermissionProfile preapproved = approvedAdditionalPermissions(context)
                .orElse(AdditionalPermissionProfile.empty());
            return Optional.of(ToolPermissionCoordinator.Result.allowed(
                PermissionGateResult.allow(),
                mergeAdditionalPermissions(preapproved, additionalPermissions)
            ));
        } catch (IllegalArgumentException exception) {
            return Optional.of(ToolPermissionCoordinator.Result.denied(PermissionGateResult.deny(exception.getMessage())));
        }
    }

    private boolean appliesTo(ToolUseRequest request) {
        return request != null
            && "bash".equals(request.toolName())
            && SandboxPermissions.fromToolValue(stringInput(request.input(), INPUT_SANDBOX_PERMISSIONS))
                == SandboxPermissions.WITH_ADDITIONAL_PERMISSIONS;
    }

    private Optional<AdditionalPermissionProfile> approvedAdditionalPermissions(ToolUseContext context) {
        Object marker = context.metadata().get(METADATA_APPROVED_ADDITIONAL_PERMISSIONS);
        boolean approved = false;
        if (marker instanceof Boolean booleanMarker) {
            approved = booleanMarker;
        } else if (marker instanceof String stringMarker) {
            approved = Boolean.parseBoolean(stringMarker);
        }
        if (!approved) {
            return Optional.empty();
        }
        Object value = context.metadata().get(METADATA_ADDITIONAL_PERMISSIONS);
        return value instanceof AdditionalPermissionProfile permissions ? Optional.of(permissions) : Optional.empty();
    }

    private AdditionalPermissionProfile mergeAdditionalPermissions(
        AdditionalPermissionProfile first,
        AdditionalPermissionProfile second
    ) {
        AdditionalPermissionProfile left = first == null ? AdditionalPermissionProfile.empty() : first;
        AdditionalPermissionProfile right = second == null ? AdditionalPermissionProfile.empty() : second;
        return new AdditionalPermissionProfile(
            mergeFileSystem(left, right),
            mergeNetwork(left, right)
        );
    }

    private Optional<FileSystemPermissionPolicy> mergeFileSystem(
        AdditionalPermissionProfile left,
        AdditionalPermissionProfile right
    ) {
        if (left.fileSystem().isEmpty()) {
            return right.fileSystem();
        }
        if (right.fileSystem().isEmpty()) {
            return left.fileSystem();
        }
        FileSystemPermissionPolicy leftPolicy = left.fileSystem().orElseThrow();
        FileSystemPermissionPolicy rightPolicy = right.fileSystem().orElseThrow();
        if (leftPolicy.kind() != rightPolicy.kind()
            || leftPolicy.kind() != FileSystemPolicyKind.RESTRICTED) {
            return right.fileSystem();
        }
        ArrayList<FileSystemPermissionEntry> entries = new ArrayList<>(leftPolicy.entries());
        entries.addAll(rightPolicy.entries());
        return Optional.of(FileSystemPermissionPolicy.restricted(entries));
    }

    private Optional<NetworkPermissionPolicy> mergeNetwork(
        AdditionalPermissionProfile left,
        AdditionalPermissionProfile right
    ) {
        if (left.network().isEmpty()) {
            return right.network();
        }
        if (right.network().isEmpty()) {
            return left.network();
        }
        if (left.network().orElseThrow().mode() == NetworkPolicyMode.ENABLED
            || right.network().orElseThrow().mode() == NetworkPolicyMode.ENABLED) {
            return Optional.of(NetworkPermissionPolicy.enabled());
        }
        return left.network();
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString();
    }

}
