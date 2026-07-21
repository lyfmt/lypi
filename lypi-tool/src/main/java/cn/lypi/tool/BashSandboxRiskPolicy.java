package cn.lypi.tool;

import cn.lypi.contracts.runtime.SandboxPermissions;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 判定默认 Bash 请求是否应由沙箱策略拒绝。
 *
 * NOTE: 该策略只处理 USE_DEFAULT；显式 requireEscalated 必须进入沙箱提权审批流。
 */
final class BashSandboxRiskPolicy {
    private static final String RETRY_WITH = "sandboxPermissions=requireEscalated";
    private static final String RETRY_HINT = "provide a user-facing justification";

    Optional<PermissionDecision> decide(ToolUseRequest request, ToolUseContext context, PermissionDecision securityDecision) {
        if (!isDefaultBashRequest(request)) {
            return Optional.empty();
        }
        PermissionRuntimeState runtimeState = runtimeState(context);
        if (runtimeState.legacyBehavior().allowExplicitEscalationWithoutPrompt()) {
            return Optional.empty();
        }
        BashRiskAnalysis bashRisk = bashRisk(securityDecision);
        if (runtimeState.legacyBehavior().defaultBashRequiresEscalation()) {
            return Optional.of(deny(
                "AUTO 权限模式下默认 Bash 请求需要显式沙箱提权。",
                bashRisk
            ));
        }
        if (isCodexDangerous(bashRisk)) {
            return Optional.of(deny(
                "默认执行模式下危险 Bash 命令需要显式沙箱提权。",
                bashRisk
            ));
        }
        return Optional.empty();
    }

    private boolean isDefaultBashRequest(ToolUseRequest request) {
        return request != null
            && "bash".equals(request.toolName())
            && SandboxPermissions.fromToolValue(stringInput(request.input(), "sandboxPermissions")) == SandboxPermissions.USE_DEFAULT;
    }

    private PermissionRuntimeState runtimeState(ToolUseContext context) {
        Object runtimeState = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_RUNTIME_STATE);
        if (runtimeState instanceof PermissionRuntimeState permissionRuntimeState) {
            return permissionRuntimeState;
        }
        Object value = context.metadata().get(ToolRuntimeContextFactory.METADATA_PERMISSION_MODE);
        if (value instanceof PermissionMode permissionMode) {
            return PermissionRuntimeState.forMode(permissionMode);
        }
        if (value instanceof String permissionMode) {
            return PermissionRuntimeState.forMode(PermissionMode.fromJson(permissionMode));
        }
        return PermissionRuntimeState.forMode(PermissionMode.ASK);
    }

    private BashRiskAnalysis bashRisk(PermissionDecision securityDecision) {
        if (securityDecision == null) {
            return null;
        }
        Object value = securityDecision.metadata().get("bashRisk");
        return value instanceof BashRiskAnalysis bashRisk ? bashRisk : null;
    }

    private boolean isCodexDangerous(BashRiskAnalysis bashRisk) {
        if (bashRisk == null) {
            return false;
        }
        return bashRisk.parsedCommands().stream().anyMatch(this::isDangerousSegment);
    }

    private boolean isDangerousSegment(String command) {
        List<String> words = words(command);
        if (words.isEmpty()) {
            return false;
        }
        if (isShell(words.getFirst()) && words.size() >= 3 && ("-lc".equals(words.get(1)) || "-c".equals(words.get(1)))) {
            return shellScriptIsDangerous(command.substring(command.indexOf(words.get(2))));
        }
        if ("sudo".equals(words.getFirst())) {
            return isDangerousSegment(words.subList(1, words.size()));
        }
        return isDangerousWords(words);
    }

    private boolean isDangerousSegment(List<String> words) {
        if (words.isEmpty()) {
            return false;
        }
        if (isShell(words.getFirst()) && words.size() >= 3 && ("-lc".equals(words.get(1)) || "-c".equals(words.get(1)))) {
            return shellScriptIsDangerous(String.join(" ", words.subList(2, words.size())));
        }
        if ("sudo".equals(words.getFirst())) {
            return isDangerousSegment(words.subList(1, words.size()));
        }
        return isDangerousWords(words);
    }

    private boolean shellScriptIsDangerous(String script) {
        String normalized = stripOuterQuotes(script);
        for (String segment : normalized.split("\\s*(?:&&|\\|\\||;|\\n|(?<![&])&(?!&)|(?<!\\|)\\|(?!\\|))\\s*")) {
            if (isDangerousSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    private String stripOuterQuotes(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private boolean isShell(String executable) {
        return "bash".equals(executable) || "sh".equals(executable) || "zsh".equals(executable);
    }

    private boolean isDangerousWords(List<String> words) {
        return words.size() >= 2 && "rm".equals(words.getFirst()) && ("-f".equals(words.get(1)) || "-rf".equals(words.get(1)));
    }

    private List<String> words(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        return List.of(command.trim().split("\\s+"));
    }

    private PermissionDecision deny(String message, BashRiskAnalysis bashRisk) {
        Map<String, Object> metadata = bashRisk == null
            ? Map.of(
                "sandboxDenied", true,
                "retryWith", RETRY_WITH,
                "retryHint", RETRY_HINT
            )
            : Map.of(
                "sandboxDenied", true,
                "retryWith", RETRY_WITH,
                "retryHint", RETRY_HINT,
                "bashRisk", bashRisk
            );
        return new PermissionDecision(
            PermissionBehavior.DENY,
            PermissionDecisionReason.BASH_RISK,
            message + "\nsandboxDenied=true\nretryWith=" + RETRY_WITH + "\nretryHint=" + RETRY_HINT,
            Optional.<PermissionUpdate>empty(),
            metadata
        );
    }

    private String stringInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        return value == null ? "" : value.toString().trim();
    }
}
