package cn.lypi.security;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class BashRuleMatcher {
    private final BashCommandNormalizer normalizer;

    BashRuleMatcher(BashCommandNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    boolean matches(PermissionRule rule, ToolUseRequest request, BashRiskAnalysis analysis) {
        String ruleToolName = rule.value().toolName();
        if (ruleToolName != null && !ruleToolName.equals("*") && !ruleToolName.equals(request.toolName())) {
            return false;
        }
        String pattern = rule.value().pattern();
        if (pattern == null || pattern.isBlank()) {
            return true;
        }

        Pattern wildcard = wildcard(pattern);
        if (rule.behavior() == PermissionBehavior.ALLOW) {
            return matchesAllow(wildcard, commandInput(request), analysis);
        }
        return matchesDenyOrAsk(wildcard, commandInput(request), analysis);
    }

    private boolean matchesAllow(Pattern wildcard, String rawCommand, BashRiskAnalysis analysis) {
        if (analysis != null && analysis.parsedCommands().size() > 1) {
            return false;
        }
        for (String candidate : commandCandidates(rawCommand, analysis, false)) {
            if (wildcard.matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesDenyOrAsk(Pattern wildcard, String rawCommand, BashRiskAnalysis analysis) {
        for (String candidate : commandCandidates(rawCommand, analysis, true)) {
            if (wildcard.matcher(candidate).matches()) {
                return true;
            }
        }
        return false;
    }

    private List<String> commandCandidates(String rawCommand, BashRiskAnalysis analysis, boolean includeSegments) {
        List<String> candidates = new ArrayList<>();
        addIfMissing(candidates, rawCommand);
        String normalized = normalizer.normalizeRaw(rawCommand);
        addIfMissing(candidates, normalized);
        addIfMissing(candidates, normalizer.stripSafeWrappers(normalized));
        if (includeSegments && analysis != null) {
            for (String parsedCommand : analysis.parsedCommands()) {
                addIfMissing(candidates, parsedCommand);
                addIfMissing(candidates, normalizer.stripSafeWrappers(parsedCommand));
            }
        }
        return candidates;
    }

    private String commandInput(ToolUseRequest request) {
        Object command = request.input().get("command");
        if (command == null) {
            command = request.input().get("cmd");
        }
        return command == null ? "" : command.toString();
    }

    private Pattern wildcard(String pattern) {
        StringBuilder regex = new StringBuilder();
        for (char character : pattern.toCharArray()) {
            if (character == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(character)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private void addIfMissing(List<String> candidates, String candidate) {
        if (candidate != null && !candidate.isBlank() && !candidates.contains(candidate)) {
            candidates.add(candidate);
        }
    }
}
