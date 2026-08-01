package cn.lypi.security;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Determines whether a statically known Bash command may enter the managed sandbox without review.
 */
final class BashSandboxEligibilityPolicy {
    private static final Set<String> SANDBOX_ALLOWED_RISKY_COMMANDS = Set.of(
        "curl",
        "wget",
        "git push",
        "sudo",
        "rm",
        "rmdir",
        "shred",
        "chmod",
        "chown"
    );
    private static final Set<String> REVIEW_REQUIRED_COMMANDS = Set.of(
        "dd",
        "mkfs",
        "npm install",
        "pip install"
    );
    private static final Set<String> SUDO_OPTIONS_WITH_VALUE = Set.of(
        "-u",
        "--user",
        "-g",
        "--group",
        "-h",
        "--host",
        "-p",
        "--prompt",
        "-r",
        "--role",
        "-t",
        "--type",
        "-C",
        "--close-from",
        "-D",
        "--chdir",
        "-R",
        "--chroot"
    );
    private static final Set<String> SUDO_OPTIONS_WITHOUT_VALUE = Set.of(
        "-A",
        "--askpass",
        "-b",
        "--background",
        "-E",
        "--preserve-env",
        "-e",
        "--edit",
        "-H",
        "--set-home",
        "-K",
        "--remove-timestamp",
        "-k",
        "--reset-timestamp",
        "-l",
        "--list",
        "-n",
        "--non-interactive",
        "-S",
        "--stdin",
        "-V",
        "--version",
        "-v",
        "--validate"
    );

    private final BashRiskAnalyzer analyzer;
    private final BashCommandNormalizer normalizer;

    BashSandboxEligibilityPolicy(BashRiskAnalyzer analyzer) {
        this(analyzer, new BashCommandNormalizer());
    }

    BashSandboxEligibilityPolicy(BashRiskAnalyzer analyzer, BashCommandNormalizer normalizer) {
        this.analyzer = analyzer;
        this.normalizer = normalizer;
    }

    boolean allows(BashRiskAnalysis analysis) {
        BashCommandNormalizer.CommandScan scan = analysis == null || analysis.normalizedCommand() == null
            ? null
            : normalizer.scan(analysis.normalizedCommand());
        if (analysis == null
            || analysis.normalizedCommand() == null
            || scan == null
            || scan.ambiguous()
            || containsDynamicExpansion(scan.analyzableCommand())
            || !analysis.staticallyKnown()
            || analysis.riskLevel() == BashRiskLevel.UNKNOWN) {
            return false;
        }
        List<String> segments = scan.segments();
        return !segments.isEmpty() && segments.stream().allMatch(this::allowsSegment);
    }

    private boolean allowsSegment(String rawSegment) {
        String segment = normalizer.stripLeadingEnvironmentAssignments(rawSegment.trim());
        segment = normalizer.stripSafeWrappers(segment);
        StaticCommand command = StaticCommand.parse(segment);
        if (!command.staticallyKnown() || requiresReview(command)) {
            return false;
        }
        if (command.isSudo()) {
            return SANDBOX_ALLOWED_RISKY_COMMANDS.contains("sudo")
                && command.staticOperand().map(this::allowsSegment).orElse(false);
        }
        BashRiskAnalysis segmentRisk = analyzer.analyze(segment);
        if (segmentRisk == null || !segmentRisk.staticallyKnown()) {
            return false;
        }
        return switch (segmentRisk.riskLevel()) {
            case LOW, MEDIUM -> true;
            case HIGH, DESTRUCTIVE -> SANDBOX_ALLOWED_RISKY_COMMANDS.contains(command.key());
            case UNKNOWN -> false;
        };
    }

    private boolean requiresReview(StaticCommand command) {
        return REVIEW_REQUIRED_COMMANDS.contains(command.key())
            || "mkfs".equals(command.executable())
            || command.executable().startsWith("mkfs.")
            || (("npm".equals(command.executable()) || "pip".equals(command.executable()))
                && command.hasWord("install"));
    }

    private boolean containsDynamicExpansion(String command) {
        return command.indexOf('$') >= 0 || command.indexOf('`') >= 0;
    }

    private record StaticCommand(
        String executable,
        String key,
        boolean staticallyKnown,
        List<String> words
    ) {
        private static StaticCommand parse(String segment) {
            if (segment == null || segment.isBlank()) {
                return unknown();
            }
            List<String> words = List.of(segment.trim().split("\\s+"));
            String executable = basename(words.getFirst());
            if (executable == null || !staticWord(words.getFirst())) {
                return unknown();
            }
            String key = executable;
            if (("git".equals(executable) || "npm".equals(executable) || "pip".equals(executable))
                && words.size() > 1
                && staticWord(words.get(1))) {
                key = executable + " " + words.get(1).toLowerCase(Locale.ROOT);
            }
            return new StaticCommand(executable, key, true, words);
        }

        private boolean isSudo() {
            return "sudo".equals(executable);
        }

        private boolean hasWord(String expected) {
            return words.stream()
                .skip(1)
                .map(word -> word.toLowerCase(Locale.ROOT))
                .anyMatch(expected::equals);
        }

        private Optional<String> staticOperand() {
            if (!isSudo()) {
                return Optional.empty();
            }
            int index = 1;
            while (index < words.size()) {
                String word = words.get(index);
                if ("--".equals(word)) {
                    index++;
                    break;
                }
                if (!word.startsWith("-") || "-".equals(word)) {
                    break;
                }
                if (optionWithInlineValue(word)) {
                    index++;
                    continue;
                }
                if (SUDO_OPTIONS_WITH_VALUE.contains(word)) {
                    index += 2;
                    continue;
                }
                if (SUDO_OPTIONS_WITHOUT_VALUE.contains(word)) {
                    index++;
                    continue;
                }
                return Optional.empty();
            }
            if (index >= words.size()) {
                return Optional.empty();
            }
            return Optional.of(String.join(" ", words.subList(index, words.size())));
        }

        private static boolean optionWithInlineValue(String word) {
            if (word.startsWith("--") && word.contains("=")) {
                return SUDO_OPTIONS_WITH_VALUE.stream().anyMatch(option -> word.startsWith(option + "="));
            }
            return word.matches("-(?:u|g|h|p|r|t|C|D|R).+");
        }

        private static String basename(String word) {
            try {
                Path path = Path.of(word);
                Path fileName = path.getFileName();
                return fileName == null ? null : fileName.toString().toLowerCase(Locale.ROOT);
            } catch (InvalidPathException exception) {
                return null;
            }
        }

        private static boolean staticWord(String word) {
            return word != null
                && !word.isBlank()
                && word.indexOf('\'') < 0
                && word.indexOf('"') < 0
                && word.indexOf('\\') < 0
                && word.indexOf('$') < 0
                && word.indexOf('`') < 0;
        }

        private static StaticCommand unknown() {
            return new StaticCommand("", "", false, List.of());
        }
    }
}
