package cn.lypi.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验 Bash exec policy prefix 是否可持久化和匹配。
 */
final class BashPrefixRuleValidator {
    static final List<List<String>> BANNED_PREFIXES = List.of(
        List.of("bash", "-lc"),
        List.of("bash"),
        List.of("sh"),
        List.of("sh", "-c"),
        List.of("zsh"),
        List.of("zsh", "-c"),
        List.of("python"),
        List.of("python3"),
        List.of("git")
    );

    private BashPrefixRuleValidator() {
    }

    static List<String> normalizeTokens(List<String> tokens) {
        List<String> normalized = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            normalized.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    static boolean isValid(List<String> tokens) {
        List<String> normalized = normalizeTokens(tokens);
        return normalized.size() >= 2 && !BANNED_PREFIXES.contains(normalized);
    }
}
