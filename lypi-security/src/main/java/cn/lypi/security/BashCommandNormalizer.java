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
        if (normalizedCommand.isBlank()) {
            return List.of();
        }
        List<String> commands = new ArrayList<>();
        for (String part : normalizedCommand.split("\\s*(?:&&|\\|\\||;|\\n|(?<![&])&(?!&)|(?<!\\|)\\|(?!\\|))\\s*")) {
            String command = stripSafeWrappers(part.trim());
            if (!command.isBlank()) {
                commands.add(command);
            }
        }
        return commands;
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
}
