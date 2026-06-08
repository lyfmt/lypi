package cn.lypi.transport.tui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MarkdownRenderer {
    private static final Pattern LINK = Pattern.compile("\\[([^]]+)]\\(([^)]+)\\)");

    List<String> render(String markdown, int width) {
        List<String> output = new ArrayList<>();
        boolean inFence = false;
        boolean diffFence = false;
        List<String> lines = markdown == null ? List.of() : markdown.lines().toList();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index).stripTrailing();
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                diffFence = inFence && (trimmed.contains("diff") || trimmed.contains("patch"));
                continue;
            }
            if (inFence) {
                output.add(renderCodeLine(line, diffFence));
                continue;
            }
            if (isSetextMarker(trimmed)
                && index > 0
                && isSetextCandidate(lines.get(index - 1).trim())) {
                continue;
            }
            if (index + 1 < lines.size() && isSetextCandidate(trimmed) && isSetextMarker(lines.get(index + 1))) {
                output.add(stripInline(line.strip()));
                continue;
            }
            if (trimmed.isBlank()) {
                continue;
            }
            if (isHorizontalRule(trimmed)) {
                output.add("─".repeat(Math.max(1, width)));
                continue;
            }
            if (isTableHeader(lines, index)) {
                output.add(renderTableRow(line, width));
                index++;
                while (index + 1 < lines.size() && lines.get(index + 1).trim().startsWith("|")) {
                    index++;
                    output.add(renderTableRow(lines.get(index), width));
                }
                continue;
            }
            if (trimmed.startsWith(">")) {
                String quote = stripInline(trimmed.substring(1).strip());
                for (String wrapped : wrap(quote, Math.max(1, width - 2))) {
                    output.add("| " + wrapped);
                }
                continue;
            }
            output.addAll(wrap(stripBlockPrefix(stripInline(trimmed)), width));
        }
        return output;
    }

    private String renderCodeLine(String line, boolean diffFence) {
        if (!diffFence) {
            return line;
        }
        if (line.startsWith("+")) {
            return "\033[32m" + line + "\033[0m";
        }
        if (line.startsWith("-")) {
            return "\033[31m" + line + "\033[0m";
        }
        return line;
    }

    private String stripBlockPrefix(String line) {
        String value = line.replaceFirst("^#{1,6}\\s+", "");
        value = value.replaceFirst("\\s+#{1,6}$", "");
        value = value.replaceFirst("^-\\s+\\[([ xX])]\\s+", "[$1] ");
        value = value.replaceFirst("^[-*+]\\s+", "");
        return value;
    }

    private String stripInline(String line) {
        String escapedStar = "\u0000STAR\u0000";
        String escapedUnderscore = "\u0000UNDERSCORE\u0000";
        String value = line
            .replace("\\*", escapedStar)
            .replace("\\_", escapedUnderscore)
            .replace("`", "")
            .replace("**", "")
            .replace("__", "")
            .replace("*", "")
            .replace("_", "")
            .replace("~~", "");
        Matcher matcher = LINK.matcher(value);
        StringBuffer buffer = new StringBuffer();
        Set<String> emittedUrls = new LinkedHashSet<>();
        while (matcher.find()) {
            String label = matcher.group(1);
            String url = matcher.group(2);
            String replacement = emittedUrls.add(url) ? label + " " + url : "";
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString()
            .replace(escapedStar, "*")
            .replace(escapedUnderscore, "_")
            .replaceAll("\\s+", " ")
            .strip();
    }

    private String renderTableRow(String line, int width) {
        String cleaned = line.strip();
        if (cleaned.startsWith("|")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.endsWith("|")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        String rendered = String.join("  ", List.of(cleaned.split("\\|")).stream()
            .map(String::strip)
            .toList());
        return AnsiWidth.truncate(rendered, width);
    }

    private boolean isTableHeader(List<String> lines, int index) {
        return index + 1 < lines.size()
            && lines.get(index).trim().startsWith("|")
            && lines.get(index + 1).matches("\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*");
    }

    private boolean isHorizontalRule(String line) {
        return line.matches("[-*_]\\s*[-*_]\\s*[-*_][\\s\\-*_]*");
    }

    private boolean isSetextMarker(String line) {
        return line != null && line.trim().matches("[=-]{3,}");
    }

    private boolean isSetextCandidate(String line) {
        return !line.startsWith(">")
            && !line.startsWith("|")
            && !line.matches("^[-*+]\\s+.*")
            && !line.matches("^\\d+\\.\\s+.*");
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentWidth = 0;
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            String value = new String(Character.toChars(codePoint));
            int codePointWidth = AnsiWidth.displayWidth(value);
            if (currentWidth > 0 && currentWidth + codePointWidth > width) {
                lines.add(current.toString().stripTrailing());
                current.setLength(0);
                currentWidth = 0;
            }
            current.append(value);
            currentWidth += codePointWidth;
            index += Character.charCount(codePoint);
        }
        lines.add(current.toString().stripTrailing());
        return lines;
    }
}
