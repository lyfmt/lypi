package cn.lypi.transport.tui;

import cn.lypi.contracts.skill.SkillDescriptor;
import cn.lypi.contracts.skill.SkillMention;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

final class SkillMentionParser {
    private static final Set<String> ENVIRONMENT_VARIABLES = Set.of(
        "PATH",
        "HOME",
        "USER",
        "PWD",
        "SHELL",
        "TMPDIR",
        "TEMP",
        "TMP",
        "LANG",
        "TERM",
        "XDG_CONFIG_HOME"
    );

    private final List<SkillDescriptor> skills;

    SkillMentionParser(List<SkillDescriptor> skills) {
        this.skills = skills == null ? List.of() : List.copyOf(skills);
    }

    Optional<SkillMentionToken> activeToken(String draft, int cursor) {
        String text = draft == null ? "" : draft;
        int safeCursor = Math.max(0, Math.min(cursor, text.length()));
        int start = safeCursor - 1;
        while (start >= 0 && isTokenChar(text.charAt(start))) {
            start--;
        }
        if (start < 0 || text.charAt(start) != '$') {
            return Optional.empty();
        }
        if (start > 0 && !Character.isWhitespace(text.charAt(start - 1))) {
            return Optional.empty();
        }
        String prefix = text.substring(start + 1, safeCursor);
        if (prefix.isEmpty() || ENVIRONMENT_VARIABLES.contains(prefix)) {
            return Optional.empty();
        }
        return Optional.of(new SkillMentionToken(start, safeCursor, prefix));
    }

    List<SkillDescriptor> matches(String prefix) {
        String normalized = prefix == null ? "" : prefix;
        return skills.stream()
            .filter(skill -> skill.name().startsWith(normalized))
            .toList();
    }

    List<SkillMention> explicitMentions(
        String input,
        List<SkillMentionBinding> bindings,
        SkillMentionSuppressions suppressions
    ) {
        Map<String, SkillMention> mentions = new LinkedHashMap<>();
        for (SkillMentionBinding binding : bindings == null ? List.<SkillMentionBinding>of() : bindings) {
            SkillMention mention = binding.toMention();
            mentions.put(mention.name() + "\n" + mention.skillFile(), mention);
        }
        for (SkillMentionToken token : tokens(input)) {
            if (suppressions != null && suppressions.suppressed(token)) {
                continue;
            }
            if (coveredByBinding(token, bindings)) {
                continue;
            }
            List<SkillDescriptor> exact = skills.stream()
                .filter(skill -> skill.name().equals(token.prefix()))
                .toList();
            if (exact.size() == 1) {
                SkillDescriptor skill = exact.getFirst();
                SkillMention mention = new SkillMention(skill.name(), skill.skillFile());
                mentions.put(mention.name() + "\n" + mention.skillFile(), mention);
            }
        }
        return List.copyOf(mentions.values());
    }

    private boolean coveredByBinding(SkillMentionToken token, List<SkillMentionBinding> bindings) {
        if (bindings == null) {
            return false;
        }
        return bindings.stream().anyMatch(binding -> binding.start() == token.start() && binding.end() == token.end());
    }

    private List<SkillMentionToken> tokens(String input) {
        String text = input == null ? "" : input;
        List<SkillMentionToken> tokens = new ArrayList<>();
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) != '$' || (index > 0 && !Character.isWhitespace(text.charAt(index - 1)))) {
                index++;
                continue;
            }
            int end = index + 1;
            while (end < text.length() && isTokenChar(text.charAt(end))) {
                end++;
            }
            String prefix = text.substring(index + 1, end);
            if (!prefix.isEmpty() && !ENVIRONMENT_VARIABLES.contains(prefix)) {
                tokens.add(new SkillMentionToken(index, end, prefix));
            }
            index = Math.max(end, index + 1);
        }
        return tokens;
    }

    private boolean isTokenChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-' || value == ':';
    }
}
