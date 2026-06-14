package cn.lypi.contracts.tui;

import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionOptionKind;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public record PermissionPromptView(
    String requestId,
    String toolUseId,
    String reason,
    String rule,
    String defaultOptionId,
    String cancelOptionId,
    List<PermissionOption> options,
    String selectedOptionId
) {
    public PermissionPromptView {
        requestId = requestId == null || requestId.isBlank() ? toolUseId : requestId;
        reason = reason == null ? "" : reason;
        rule = rule == null ? "" : rule;
        defaultOptionId = defaultOptionId == null || defaultOptionId.isBlank() ? rule : defaultOptionId;
        cancelOptionId = cancelOptionId == null ? "" : cancelOptionId;
        options = options == null ? List.of() : List.copyOf(options);
        selectedOptionId = normalizeSelectedOptionId(selectedOptionId, defaultOptionId, options);
    }

    public PermissionPromptView(
        String requestId,
        String toolUseId,
        String reason,
        String rule,
        String defaultOptionId,
        String cancelOptionId
    ) {
        this(
            requestId,
            toolUseId,
            reason,
            rule,
            defaultOptionId,
            cancelOptionId,
            compatibilityOptions(defaultOptionId, cancelOptionId),
            defaultOptionId
        );
    }

    public PermissionPromptView(String toolUseId, String reason, String rule, String defaultOptionId, String cancelOptionId) {
        this(toolUseId, toolUseId, reason, rule, defaultOptionId, cancelOptionId);
    }

    public PermissionPromptView(String toolUseId, String reason, String rule) {
        this(toolUseId, toolUseId, reason, rule, rule, "");
    }

    private static String normalizeSelectedOptionId(
        String selectedOptionId,
        String defaultOptionId,
        List<PermissionOption> options
    ) {
        String candidate = selectedOptionId == null || selectedOptionId.isBlank() ? defaultOptionId : selectedOptionId;
        if (options.isEmpty()) {
            return candidate == null ? "" : candidate;
        }
        if (candidate == null || candidate.isBlank()) {
            return options.getFirst().optionId();
        }
        boolean exists = options.stream().anyMatch(option -> candidate.equals(option.optionId()));
        return exists ? candidate : options.getFirst().optionId();
    }

    private static List<PermissionOption> compatibilityOptions(String defaultOptionId, String cancelOptionId) {
        List<PermissionOption> options = new ArrayList<>();
        if (defaultOptionId != null && !defaultOptionId.isBlank()) {
            options.add(new PermissionOption(
                defaultOptionId,
                PermissionOptionKind.ALLOW_ONCE,
                defaultOptionId,
                "",
                Optional.empty(),
                Map.of()
            ));
        }
        if (cancelOptionId != null && !cancelOptionId.isBlank()
            && options.stream().noneMatch(option -> cancelOptionId.equals(option.optionId()))) {
            options.add(new PermissionOption(
                cancelOptionId,
                PermissionOptionKind.CANCEL,
                cancelOptionId,
                "",
                Optional.empty(),
                Map.of()
            ));
        }
        return List.copyOf(options);
    }
}
