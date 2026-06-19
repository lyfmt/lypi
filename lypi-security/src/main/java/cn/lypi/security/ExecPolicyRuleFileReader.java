package cn.lypi.security;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 exec policy 规则文件中的 prefix allow 规则。
 */
public final class ExecPolicyRuleFileReader {
    private static final Pattern PREFIX_RULE = Pattern.compile(
        "prefix_rule\\(\\s*pattern=\\[(?<pattern>.*)]\\s*,\\s*decision=\"allow\"\\s*\\)"
    );
    private static final Pattern QUOTED_TOKEN = Pattern.compile("\"((?:\\\\.|[^\"])*)\"");
    private final LineReader lineReader;

    public ExecPolicyRuleFileReader() {
        this(Files::readAllLines);
    }

    ExecPolicyRuleFileReader(LineReader lineReader) {
        this.lineReader = Objects.requireNonNull(lineReader, "lineReader");
    }

    /**
     * 从规则文件读取可识别的 prefix allow 规则。
     */
    public List<PermissionRule> read(Path rulesFile) {
        if (rulesFile == null || !Files.isRegularFile(rulesFile)) {
            return List.of();
        }
        try {
            List<PermissionRule> rules = new ArrayList<>();
            for (String line : lineReader.readAllLines(rulesFile)) {
                parseLine(line).ifPresent(rules::add);
            }
            return List.copyOf(rules);
        } catch (IOException exception) {
            return List.of();
        }
    }

    private java.util.Optional<PermissionRule> parseLine(String line) {
        if (line == null || line.isBlank()) {
            return java.util.Optional.empty();
        }
        Matcher matcher = PREFIX_RULE.matcher(line.trim());
        if (!matcher.matches()) {
            return java.util.Optional.empty();
        }
        List<String> tokens = tokens(matcher.group("pattern"));
        if (!BashPrefixRuleValidator.isValid(tokens)) {
            return java.util.Optional.empty();
        }
        String pattern = BashPrefixPolicy.PATTERN_PREFIX + String.join(" ", BashPrefixRuleValidator.normalizeTokens(tokens));
        return java.util.Optional.of(new PermissionRule(
            PermissionRuleSource.USER,
            PermissionBehavior.ALLOW,
            new PermissionRuleValue("bash", pattern),
            "exec policy file: " + pattern
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

    @FunctionalInterface
    interface LineReader {
        List<String> readAllLines(Path path) throws IOException;
    }
}
