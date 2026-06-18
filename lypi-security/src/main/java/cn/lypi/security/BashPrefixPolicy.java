package cn.lypi.security;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 生成和匹配 Codex 风格 Bash exec policy prefix。
 */
final class BashPrefixPolicy {
    static final String PATTERN_PREFIX = "prefix:";

    private final BashCommandNormalizer normalizer;

    BashPrefixPolicy(BashCommandNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    Optional<PermissionUpdate> suggestedUpdate(ToolUseRequest request, BashRiskAnalysis bashRisk) {
        if (bashRisk == null
            || !bashRisk.staticallyKnown()
            || bashRisk.riskLevel() == BashRiskLevel.UNKNOWN
            || bashRisk.riskLevel() == BashRiskLevel.DESTRUCTIVE
            || !bashRisk.redirectTargets().isEmpty()) {
            return Optional.empty();
        }
        List<List<String>> segments = parsedSegments(bashRisk);
        if (segments.isEmpty()) {
            return Optional.empty();
        }
        RequestPrefix requestedPrefix = requestedPrefix(request);
        if (requestedPrefix.present() && requestedPrefix.valid().isPresent()) {
            List<String> prefix = requestedPrefix.valid().orElseThrow();
            if (coversAll(prefix, segments)) {
                return Optional.of(update(prefix, "允许 Bash prefix: " + render(prefix)));
            }
            return Optional.empty();
        }
        if (requestedPrefix.present()) {
            return Optional.empty();
        }
        return derivedPrefix(segments)
            .map(prefix -> update(prefix, "允许 Bash prefix: " + render(prefix)));
    }

    boolean matchesPrefixRule(PermissionRule rule, BashRiskAnalysis bashRisk) {
        if (bashRisk == null
            || !bashRisk.staticallyKnown()
            || bashRisk.riskLevel() == BashRiskLevel.UNKNOWN
            || bashRisk.riskLevel() == BashRiskLevel.DESTRUCTIVE) {
            return false;
        }
        Optional<List<String>> prefix = prefixFromRule(rule);
        return prefix.isPresent() && coversAll(prefix.orElseThrow(), parsedSegments(bashRisk));
    }

    boolean isPrefixRule(PermissionRule rule) {
        return prefixFromRule(rule).isPresent();
    }

    RequestPrefix requestedPrefix(ToolUseRequest request) {
        Object value = request.input().get("prefix_rule");
        if (!(value instanceof Iterable<?> iterable)) {
            return new RequestPrefix(false, Optional.empty());
        }
        List<String> tokens = new ArrayList<>();
        for (Object candidate : iterable) {
            if (!(candidate instanceof String token) || token.isBlank()) {
                return new RequestPrefix(true, Optional.empty());
            }
            tokens.add(token.trim());
        }
        return new RequestPrefix(true, validPrefix(tokens));
    }

    private Optional<List<String>> derivedPrefix(List<List<String>> segments) {
        if (segments.size() != 1) {
            return Optional.empty();
        }
        List<String> segment = segments.getFirst();
        if (segment.size() < 2) {
            return Optional.empty();
        }
        return validPrefix(segment.subList(0, 2));
    }

    private Optional<List<String>> prefixFromRule(PermissionRule rule) {
        if (rule == null || rule.value() == null || rule.value().pattern() == null) {
            return Optional.empty();
        }
        String pattern = rule.value().pattern();
        if (!pattern.startsWith(PATTERN_PREFIX)) {
            return Optional.empty();
        }
        String prefix = pattern.substring(PATTERN_PREFIX.length()).trim();
        if (prefix.isBlank()) {
            return Optional.empty();
        }
        return validPrefix(List.of(prefix.split("\\s+")));
    }

    private Optional<List<String>> validPrefix(List<String> tokens) {
        List<String> normalized = BashPrefixRuleValidator.normalizeTokens(tokens);
        if (!BashPrefixRuleValidator.isValid(normalized)) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(normalized));
    }

    private boolean coversAll(List<String> prefix, List<List<String>> segments) {
        if (segments.isEmpty()) {
            return false;
        }
        for (List<String> segment : segments) {
            if (!startsWith(segment, prefix)) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWith(List<String> segment, List<String> prefix) {
        if (prefix.size() > segment.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!segment.get(index).equals(prefix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private List<List<String>> parsedSegments(BashRiskAnalysis bashRisk) {
        if (bashRisk == null || bashRisk.parsedCommands() == null) {
            return List.of();
        }
        List<List<String>> segments = new ArrayList<>();
        for (String parsedCommand : bashRisk.parsedCommands()) {
            List<String> tokens = BashPrefixRuleValidator.normalizeTokens(
                List.of(normalizer.stripSafeWrappers(parsedCommand).split("\\s+"))
            );
            if (!tokens.isEmpty()) {
                segments.add(tokens);
            }
        }
        return List.copyOf(segments);
    }

    private PermissionUpdate update(List<String> prefix, String reason) {
        return new PermissionUpdate(
            PermissionRuleSource.USER,
            new PermissionRule(
                PermissionRuleSource.USER,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", PATTERN_PREFIX + render(prefix)),
                reason
            )
        );
    }

    private String render(List<String> prefix) {
        return String.join(" ", prefix);
    }

    private record RequestPrefix(
        boolean present,
        Optional<List<String>> valid
    ) {}
}
