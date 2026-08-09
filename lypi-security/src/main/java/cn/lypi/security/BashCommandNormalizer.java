package cn.lypi.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 归一化 Bash 命令以支持风险分析和规则匹配。
 *
 * NOTE: 只剥离语义上安全的前置环境变量和 wrapper，无法确认时保留原命令。
 */
final class BashCommandNormalizer {
    private static final Pattern LEADING_ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+");

    /**
     * 规范化原始命令字符串。
     */
    String normalizeRaw(String rawCommand) {
        String normalized = rawCommand == null ? "" : rawCommand.trim();
        normalized = normalized.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        normalized = stripLeadingEnvironmentAssignments(normalized);
        return lowerStaticBashCommand(normalized);
    }

    String stripLeadingEnvironmentAssignments(String command) {
        String normalized = command;
        Matcher matcher = LEADING_ENV_ASSIGNMENT.matcher(normalized);
        while (matcher.find()) {
            normalized = matcher.replaceFirst("");
            matcher = LEADING_ENV_ASSIGNMENT.matcher(normalized);
        }
        return normalized;
    }

    /**
     * 剥离前置安全 wrapper。
     */
    String stripSafeWrappers(String command) {
        List<String> words = words(command);
        int index = 0;
        while (index < words.size()) {
            String word = words.get(index);
            if ("timeout".equals(word)) {
                int next = stripTimeout(words, index);
                if (next == index) {
                    break;
                }
                index = next;
            } else if ("nice".equals(word)) {
                index = stripNice(words, index);
            } else if ("time".equals(word)) {
                index = stripTime(words, index);
            } else if ("nohup".equals(word)) {
                index++;
            } else if ("command".equals(word)) {
                index = stripCommand(words, index);
            } else if ("env".equals(word)) {
                int next = stripEnv(words, index);
                if (next == index) {
                    break;
                }
                index = next;
            } else {
                break;
            }
        }
        if (index >= words.size()) {
            return command;
        }
        return String.join(" ", words.subList(index, words.size()));
    }

    /**
     * 拆分复合命令段。
     */
    List<String> splitCommandSegments(String normalizedCommand) {
        return scan(normalizedCommand).segments();
    }

    /**
     * 扫描可静态分析的外层命令结构。
     */
    CommandScan scan(String normalizedCommand) {
        String command = normalizedCommand == null ? "" : normalizedCommand;
        HeredocScan heredoc = stripStrictQuotedHeredoc(command);
        SegmentScan segments = splitOutsideQuotes(heredoc.analyzableCommand());
        return new CommandScan(
            segments.segments(),
            heredoc.analyzableCommand(),
            heredoc.ambiguous() || segments.ambiguous()
        );
    }

    private HeredocScan stripStrictQuotedHeredoc(String command) {
        HeredocOperator operator = findHeredocOperator(command);
        if (operator == null) {
            return new HeredocScan(command, false);
        }
        if (!operator.valid()) {
            return new HeredocScan(command, true);
        }
        int headerEnd = command.indexOf('\n', operator.end());
        if (headerEnd < 0) {
            return new HeredocScan(command, true);
        }
        int lineStart = headerEnd + 1;
        while (lineStart <= command.length()) {
            int lineEnd = command.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = command.length();
            }
            if (command.substring(lineStart, lineEnd).equals(operator.delimiter())) {
                int suffixStart = lineEnd < command.length() ? lineEnd + 1 : lineEnd;
                String header = command.substring(0, operator.start())
                    + " "
                    + command.substring(operator.end(), headerEnd);
                String suffix = command.substring(suffixStart);
                String analyzable = suffix.isEmpty() ? header : header + "\n" + suffix;
                return new HeredocScan(analyzable, findHeredocOperator(analyzable) != null);
            }
            if (lineEnd == command.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return new HeredocScan(command, true);
    }

    private HeredocOperator findHeredocOperator(String command) {
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (singleQuoted) {
                if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (character == '\\') {
                escaped = true;
            } else if (character == '\'') {
                singleQuoted = true;
            } else if (character == '"') {
                doubleQuoted = true;
            } else if (character == '<' && index + 1 < command.length() && command.charAt(index + 1) == '<') {
                return parseHeredocOperator(command, index);
            }
        }
        return null;
    }

    private HeredocOperator parseHeredocOperator(String command, int start) {
        int cursor = start + 2;
        if (cursor < command.length() && (command.charAt(cursor) == '<' || command.charAt(cursor) == '-')) {
            return HeredocOperator.invalid(start);
        }
        while (cursor < command.length() && horizontalWhitespace(command.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= command.length() || command.charAt(cursor) == '\n' || command.charAt(cursor) == '-') {
            return HeredocOperator.invalid(start);
        }
        char quote = command.charAt(cursor);
        if (quote != '\'' && quote != '"') {
            return HeredocOperator.invalid(start);
        }
        int delimiterStart = ++cursor;
        while (cursor < command.length() && command.charAt(cursor) != quote && command.charAt(cursor) != '\n') {
            cursor++;
        }
        if (cursor >= command.length() || command.charAt(cursor) != quote) {
            return HeredocOperator.invalid(start);
        }
        String delimiter = command.substring(delimiterStart, cursor);
        if (!delimiter.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            return HeredocOperator.invalid(start);
        }
        int end = cursor + 1;
        if (end < command.length() && !heredocBoundary(command.charAt(end))) {
            return HeredocOperator.invalid(start);
        }
        return new HeredocOperator(start, end, delimiter, true);
    }

    private boolean horizontalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    private boolean heredocBoundary(char character) {
        return Character.isWhitespace(character) || "&|;()<>".indexOf(character) >= 0;
    }

    private SegmentScan splitOutsideQuotes(String command) {
        if (command.isBlank()) {
            return new SegmentScan(List.of(), false);
        }
        List<String> commands = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < command.length(); index++) {
            char character = command.charAt(index);
            if (singleQuoted) {
                segment.append(character);
                if (character == '\'') {
                    singleQuoted = false;
                }
                continue;
            }
            if (doubleQuoted) {
                segment.append(character);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    doubleQuoted = false;
                }
                continue;
            }
            if (escaped) {
                segment.append(character);
                escaped = false;
                continue;
            }
            if (character == '\\') {
                segment.append(character);
                escaped = true;
                continue;
            }
            if (character == '\'') {
                segment.append(character);
                singleQuoted = true;
                continue;
            }
            if (character == '"') {
                segment.append(character);
                doubleQuoted = true;
                continue;
            }
            int separatorWidth = separatorWidth(command, index);
            if (separatorWidth > 0) {
                addSegment(commands, segment);
                index += separatorWidth - 1;
                continue;
            }
            segment.append(character);
        }
        addSegment(commands, segment);
        return new SegmentScan(List.copyOf(commands), singleQuoted || doubleQuoted || escaped);
    }

    private int separatorWidth(String command, int index) {
        char character = command.charAt(index);
        if (character == '\n' || character == ';') {
            return 1;
        }
        if (character != '&' && character != '|') {
            return 0;
        }
        return index + 1 < command.length() && command.charAt(index + 1) == character ? 2 : 1;
    }

    private void addSegment(List<String> commands, StringBuilder segment) {
        String command = stripSafeWrappers(segment.toString().trim());
        if (!command.isBlank()) {
            commands.add(command);
        }
        segment.setLength(0);
    }

    private int stripTimeout(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).startsWith("-")) {
            next++;
            if (next < words.size() && requiresTimeoutOptionValue(words.get(next - 1))) {
                next++;
            }
        }
        if (next < words.size() && isDuration(words.get(next))) {
            return next + 1;
        }
        return index;
    }

    private int stripNice(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).startsWith("-")) {
            String option = words.get(next);
            next++;
            if (("-n".equals(option) || "--adjustment".equals(option)) && next < words.size()) {
                next++;
            }
        }
        return next < words.size() ? next : index;
    }

    private int stripTime(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).startsWith("-")) {
            next++;
        }
        return next < words.size() ? next : index;
    }

    private boolean requiresTimeoutOptionValue(String option) {
        return "-s".equals(option) || "--signal".equals(option) || "-k".equals(option) || "--kill-after".equals(option);
    }

    private boolean isDuration(String value) {
        return value.matches("\\d+(?:\\.\\d+)?[smhd]?");
    }

    private int stripEnv(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size()) {
            String word = words.get(next);
            if (word.matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
                next++;
            } else if (word.startsWith("-")) {
                next++;
                if (envOptionRequiresValue(word) && next < words.size()) {
                    next++;
                }
            } else {
                break;
            }
        }
        return next < words.size() ? next : index;
    }

    private boolean envOptionRequiresValue(String option) {
        return "-u".equals(option)
            || "--unset".equals(option)
            || "-C".equals(option)
            || "--chdir".equals(option)
            || "-S".equals(option)
            || "--split-string".equals(option);
    }

    private int stripCommand(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).startsWith("-")) {
            next++;
        }
        return next < words.size() ? next : index;
    }

    private String lowerStaticBashCommand(String command) {
        String lowered = lowerStaticBashCommand(command, "bash");
        return lowered == null ? command : lowered;
    }

    private String lowerStaticBashCommand(String command, String shell) {
        if (!command.startsWith(shell + " ")) {
            return null;
        }
        String rest = command.substring(shell.length()).trim();
        if (rest.startsWith("-lc ")) {
            return unquoteStaticShellArgument(rest.substring(4).trim());
        }
        if (rest.startsWith("-c ")) {
            return unquoteStaticShellArgument(rest.substring(3).trim());
        }
        return null;
    }

    private String unquoteStaticShellArgument(String argument) {
        if (argument.length() < 2) {
            return null;
        }
        char quote = argument.charAt(0);
        if ((quote != '"' && quote != '\'') || argument.charAt(argument.length() - 1) != quote) {
            return null;
        }
        String body = argument.substring(1, argument.length() - 1);
        if (quote == '\'') {
            return body.contains("'") ? null : body;
        }
        StringBuilder builder = new StringBuilder();
        boolean escaped = false;
        for (char character : body.toCharArray()) {
            if (escaped) {
                if (character == '"' || character == '\\' || character == '$' || character == '`') {
                    builder.append(character);
                } else {
                    builder.append('\\').append(character);
                }
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

    private List<String> words(String command) {
        if (command.isBlank()) {
            return List.of();
        }
        return List.of(command.split("\\s+"));
    }

    record CommandScan(
        List<String> segments,
        String analyzableCommand,
        boolean ambiguous
    ) {
        CommandScan {
            segments = segments == null ? List.of() : List.copyOf(segments);
            analyzableCommand = analyzableCommand == null ? "" : analyzableCommand;
        }
    }

    private record HeredocScan(String analyzableCommand, boolean ambiguous) {
    }

    private record HeredocOperator(int start, int end, String delimiter, boolean valid) {
        private static HeredocOperator invalid(int start) {
            return new HeredocOperator(start, start + 2, "", false);
        }
    }

    private record SegmentScan(List<String> segments, boolean ambiguous) {
    }
}
