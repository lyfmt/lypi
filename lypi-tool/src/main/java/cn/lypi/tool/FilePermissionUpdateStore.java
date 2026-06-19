package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.security.PermissionUpdate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 legacy exec policy 权限规则。
 *
 * NOTE: 新写入应使用 PermissionAmendmentStore；该类仅保留旧规则导入能力。
 */
public final class FilePermissionUpdateStore implements PermissionUpdateStore {
    private static final String PREFIX_PATTERN = "prefix:";
    private static final Pattern PREFIX_RULE = Pattern.compile(
        "prefix_rule\\(\\s*pattern=\\[(?<pattern>.*)]\\s*,\\s*decision=\"allow\"\\s*\\)"
    );
    private static final Pattern QUOTED_TOKEN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private static final List<List<String>> BANNED_PREFIXES = List.of(
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

    private final Path rulesFile;

    public FilePermissionUpdateStore(Path runtimeConfigDir) {
        Path root = runtimeConfigDir == null ? Path.of(".") : runtimeConfigDir;
        this.rulesFile = root.resolve("rules").resolve("default.rules");
    }

    @Override
    public void append(PermissionUpdate update) {
        // NOTE: Legacy store is read-only; new writes go through PermissionAmendmentStore.
    }

    /**
     * 读取 legacy `rules/default.rules` 中的 prefix allow 更新。
     */
    public List<PermissionUpdate> readPermissionUpdates() {
        if (!Files.isRegularFile(rulesFile)) {
            return List.of();
        }
        try {
            List<PermissionUpdate> updates = new ArrayList<>();
            for (String line : Files.readAllLines(rulesFile, StandardCharsets.UTF_8)) {
                parseLine(line).ifPresent(updates::add);
            }
            return List.copyOf(updates);
        } catch (IOException exception) {
            throw new IllegalStateException("权限规则读取失败: " + exception.getMessage(), exception);
        }
    }

    private java.util.Optional<PermissionUpdate> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return java.util.Optional.empty();
        }
        Matcher matcher = PREFIX_RULE.matcher(line.trim());
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        List<String> tokens = tokens(matcher.group("pattern"));
        List<String> normalizedTokens = normalizeTokens(tokens);
        if (normalizedTokens.size() < 2 || BANNED_PREFIXES.contains(normalizedTokens)) {
            return java.util.Optional.empty();
        }
        String pattern = PREFIX_PATTERN + String.join(" ", normalizedTokens);
        return java.util.Optional.of(new PermissionUpdate(
            PermissionRuleSource.USER,
            new PermissionRule(
                PermissionRuleSource.USER,
                PermissionBehavior.ALLOW,
                new PermissionRuleValue("bash", pattern),
                "允许 Bash prefix: " + pattern.substring(PREFIX_PATTERN.length())
            )
        ));
    }

    private List<String> tokens(String pattern) {
        Matcher matcher = QUOTED_TOKEN.matcher(pattern);
        List<String> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(unescape(matcher.group(1)));
        }
        return tokens;
    }

    private List<String> normalizeTokens(List<String> tokens) {
        List<String> normalized = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            normalized.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String unescape(String token) {
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (char character : token.toCharArray()) {
            if (escaped) {
                builder.append(character);
                escaped = false;
            } else if (character == '\\') {
                escaped = true;
            } else {
                builder.append(character);
            }
        }
        if (escaped) {
            builder.append('\\');
        }
        return builder.toString();
    }
}
