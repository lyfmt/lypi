package cn.lypi.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashCommandNormalizer {
    private static final Pattern LEADING_ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+");

    String normalizeRaw(String rawCommand) {
        String normalized = rawCommand == null ? "" : rawCommand.trim();
        normalized = normalized.replaceAll("[ \\t\\x0B\\f\\r]+", " ");
        return stripLeadingEnvironmentAssignments(normalized);
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
                if (("-u".equals(word) || "--unset".equals(word)) && next < words.size()) {
                    next++;
                }
            } else {
                break;
            }
        }
        return next < words.size() ? next : index;
    }

    private int stripCommand(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).startsWith("-")) {
            next++;
        }
        return next < words.size() ? next : index;
    }

    private List<String> words(String command) {
        if (command.isBlank()) {
            return List.of();
        }
        return List.of(command.split("\\s+"));
    }
}
