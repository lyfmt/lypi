package cn.lypi.security;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class BashCommandNormalizer {
    private static final Pattern LEADING_ENV_ASSIGNMENT = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*=[^\\s]+\\s+");

    String normalizeRaw(String rawCommand) {
        String normalized = rawCommand == null ? "" : rawCommand.trim().replaceAll("\\s+", " ");
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
            } else if ("nice".equals(word) || "nohup".equals(word) || "time".equals(word) || "command".equals(word)) {
                index++;
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
        for (String part : normalizedCommand.split("\\s*(?:&&|\\|\\||;|(?<!\\|)\\|(?!\\|))\\s*")) {
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

    private boolean requiresTimeoutOptionValue(String option) {
        return "-s".equals(option) || "--signal".equals(option) || "-k".equals(option) || "--kill-after".equals(option);
    }

    private boolean isDuration(String value) {
        return value.matches("\\d+(?:\\.\\d+)?[smhd]?");
    }

    private int stripEnv(List<String> words, int index) {
        int next = index + 1;
        while (next < words.size() && words.get(next).matches("[A-Za-z_][A-Za-z0-9_]*=.*")) {
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
