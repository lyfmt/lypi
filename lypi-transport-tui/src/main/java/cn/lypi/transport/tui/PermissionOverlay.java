package cn.lypi.transport.tui;

import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionOption;
import cn.lypi.contracts.security.PermissionRule;
import java.util.List;
import java.util.Optional;

final class PermissionOverlay {
    private final List<PermissionOption> options;
    private final int height;
    private int selected;
    private int viewportStart;

    PermissionOverlay(List<PermissionOption> options, int height) {
        this.options = options == null ? List.of() : List.copyOf(options);
        this.height = Math.max(1, height);
    }

    void moveDown() {
        if (selected + 1 < options.size()) {
            selected++;
            ensureSelectedVisible();
        }
    }

    String selectedOptionId() {
        if (options.isEmpty()) {
            return "";
        }
        return options.get(selected).optionId();
    }

    List<String> visibleOptionIds() {
        int end = Math.min(options.size(), viewportStart + height);
        return options.subList(viewportStart, end).stream()
            .map(PermissionOption::optionId)
            .toList();
    }

    Optional<String> submit() {
        return options.isEmpty() ? Optional.empty() : Optional.of(selectedOptionId());
    }

    Optional<String> cancel() {
        return options.stream()
            .filter(option -> "cancel".equals(option.optionId()) || option.optionId().contains("cancel"))
            .map(PermissionOption::optionId)
            .findFirst();
    }

    static String formatRule(PermissionDecision decision) {
        if (decision == null) {
            return "";
        }
        Object rule = decision.metadata() == null ? null : decision.metadata().get("rule");
        String formatted = formatRuleObject(rule);
        if (!formatted.isBlank()) {
            return formatted;
        }
        return decision.suggestedUpdate()
            .map(update -> formatPermissionRule(update.rule()))
            .orElse("");
    }

    private void ensureSelectedVisible() {
        if (selected < viewportStart) {
            viewportStart = selected;
            return;
        }
        if (selected >= viewportStart + height) {
            viewportStart = selected - height + 1;
        }
    }

    private static String formatRuleObject(Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof PermissionRule rule) {
            return formatPermissionRule(rule);
        }
        return "";
    }

    private static String formatPermissionRule(PermissionRule rule) {
        if (rule == null || rule.value() == null) {
            return "";
        }
        return rule.value().toolName() + ":" + rule.value().pattern();
    }
}
