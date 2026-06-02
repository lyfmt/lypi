package cn.lypi.security;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DefaultBashRiskAnalyzer implements BashRiskAnalyzer {
    private static final Pattern REDIRECT_TARGET = Pattern.compile("(?<!<)(?:(?:\\d+)?>>?|&>)\\s*([^\\s;&|()]+)");
    private static final Set<String> LOW_RISK_COMMANDS = Set.of(
        "cat",
        "cd",
        "find",
        "git status",
        "git diff",
        "git log",
        "ls",
        "pwd",
        "rg",
        "sed",
        "wc"
    );
    private static final Set<String> MEDIUM_RISK_COMMANDS = Set.of(
        "apply_patch",
        "cp",
        "git add",
        "git commit",
        "git mv",
        "mkdir",
        "mv",
        "touch"
    );
    private static final Set<String> HIGH_RISK_COMMANDS = Set.of(
        "curl",
        "git push",
        "npm install",
        "pip install",
        "sudo",
        "wget"
    );
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
        "chmod",
        "chown",
        "dd",
        "mkfs",
        "rm",
        "rmdir",
        "shred"
    );
    private static final Set<String> SHELL_COMMANDS = Set.of("bash", "sh", "zsh");
    private final BashCommandNormalizer normalizer;

    public DefaultBashRiskAnalyzer() {
        this(new BashCommandNormalizer());
    }

    DefaultBashRiskAnalyzer(BashCommandNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    @Override
    public BashRiskAnalysis analyze(String rawCommand) {
        String normalized = normalizer.normalizeRaw(rawCommand);
        List<String> parsedCommands = parseCommands(normalized);
        List<Path> redirectTargets = redirectTargets(normalized);
        List<String> reasons = new ArrayList<>();

        if (containsDynamicShell(normalized)) {
            reasons.add("包含动态 shell 结构");
            return analysis(normalized, parsedCommands, redirectTargets, BashRiskLevel.UNKNOWN, reasons, false);
        }

        BashRiskLevel riskLevel = BashRiskLevel.LOW;
        if (!redirectTargets.isEmpty()) {
            riskLevel = max(riskLevel, BashRiskLevel.MEDIUM);
            reasons.add("包含输出重定向");
        }

        for (String parsedCommand : parsedCommands) {
            String commandKey = commandKey(parsedCommand);
            if (matchesAny(commandKey, SHELL_COMMANDS)) {
                riskLevel = max(riskLevel, BashRiskLevel.UNKNOWN);
                addIfMissing(reasons, "管道包含 shell 执行段");
            } else if (matchesAny(commandKey, DESTRUCTIVE_COMMANDS)) {
                riskLevel = max(riskLevel, BashRiskLevel.DESTRUCTIVE);
                addIfMissing(reasons, "包含破坏性命令");
            } else if (matchesAny(commandKey, HIGH_RISK_COMMANDS)) {
                riskLevel = max(riskLevel, BashRiskLevel.HIGH);
                addIfMissing(reasons, "包含网络、提权或远端变更命令");
            } else if (matchesAny(commandKey, MEDIUM_RISK_COMMANDS)) {
                riskLevel = max(riskLevel, BashRiskLevel.MEDIUM);
                addIfMissing(reasons, "包含文件写入或移动命令");
            } else if (!matchesAny(commandKey, LOW_RISK_COMMANDS)) {
                riskLevel = max(riskLevel, BashRiskLevel.MEDIUM);
                addIfMissing(reasons, "包含未登记命令");
            }
        }

        if (reasons.isEmpty()) {
            reasons.add("未发现写入、网络、提权或破坏性结构");
        }
        return analysis(normalized, parsedCommands, redirectTargets, riskLevel, reasons, riskLevel != BashRiskLevel.UNKNOWN);
    }

    private BashRiskAnalysis analysis(
        String normalized,
        List<String> parsedCommands,
        List<Path> redirectTargets,
        BashRiskLevel riskLevel,
        List<String> reasons,
        boolean staticallyKnown
    ) {
        return new BashRiskAnalysis(
            normalized,
            List.copyOf(parsedCommands),
            List.copyOf(redirectTargets),
            riskLevel,
            List.copyOf(reasons),
            staticallyKnown
        );
    }

    private List<String> parseCommands(String normalizedCommand) {
        List<String> commands = new ArrayList<>();
        for (String part : normalizer.splitCommandSegments(normalizedCommand)) {
            String command = part.trim();
            if (!command.isBlank()) {
                commands.add(displayCommand(command));
            }
        }
        return commands;
    }

    private String displayCommand(String command) {
        String commandKey = commandKey(command);
        if (commandKey.equals("git status")) {
            return commandKey;
        }
        if (command.startsWith("mkdir -p ")) {
            return "mkdir -p";
        }
        return command;
    }

    private List<Path> redirectTargets(String normalizedCommand) {
        Matcher matcher = REDIRECT_TARGET.matcher(normalizedCommand);
        List<Path> targets = new ArrayList<>();
        while (matcher.find()) {
            targets.add(Path.of(matcher.group(1)));
        }
        return targets;
    }

    private boolean containsDynamicShell(String command) {
        return command.contains("$(")
            || command.contains("`")
            || command.contains("<(")
            || command.contains(">(")
            || command.matches(".*\\s<<-?\\s*\\S+.*")
            || command.matches(".*\\bfor\\b.*\\bdo\\b.*")
            || command.matches(".*\\bwhile\\b.*\\bdo\\b.*")
            || command.matches(".*\\bcase\\b.*\\bin\\b.*")
            || command.matches(".*\\bif\\b.*\\bthen\\b.*")
            || command.matches(".*\\b(?:bash|sh|zsh)\\s+-c\\b.*");
    }

    private String commandKey(String command) {
        String lowerCommand = command.toLowerCase(Locale.ROOT);
        String[] words = lowerCommand.split("\\s+");
        if (words.length >= 2) {
            return words[0] + " " + words[1];
        }
        return words[0];
    }

    private boolean matchesAny(String commandKey, Set<String> candidates) {
        for (String candidate : candidates) {
            if (commandKey.equals(candidate) || commandKey.startsWith(candidate + " ")) {
                return true;
            }
        }
        return false;
    }

    private BashRiskLevel max(BashRiskLevel current, BashRiskLevel candidate) {
        if (rank(candidate) > rank(current)) {
            return candidate;
        }
        return current;
    }

    private int rank(BashRiskLevel riskLevel) {
        return switch (riskLevel) {
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
            case DESTRUCTIVE -> 4;
            case UNKNOWN -> 5;
        };
    }

    private void addIfMissing(List<String> reasons, String reason) {
        if (!reasons.contains(reason)) {
            reasons.add(reason);
        }
    }
}
