package cn.lypi.tool.builtin;

import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class GrepResultFormatter {
    private static final int DEFAULT_HEAD_LIMIT = 250;

    String format(GrepQuery query, List<String> rawLines, ToolUseContext context) {
        return switch (query.outputMode()) {
            case CONTENT -> formatContent(query, rawLines, context);
            case COUNT -> formatCount(query, rawLines, context);
            case FILES_WITH_MATCHES -> formatFiles(query, rawLines, context);
        };
    }

    private String formatFiles(GrepQuery query, List<String> rawLines, ToolUseContext context) {
        Page page = page(rawLines, query);
        if (page.items().isEmpty()) {
            return "No files found";
        }
        List<String> files = page.items().stream()
            .map(line -> relativePath(line, context))
            .toList();
        StringBuilder output = new StringBuilder();
        output.append("Found ").append(files.size()).append(' ').append(files.size() == 1 ? "file" : "files");
        String limitInfo = limitInfo(page.appliedLimit(), query.offset());
        if (!limitInfo.isBlank()) {
            output.append(' ').append(limitInfo);
        }
        output.append('\n').append(String.join("\n", files));
        return output.toString();
    }

    private String formatContent(GrepQuery query, List<String> rawLines, ToolUseContext context) {
        Page page = page(rawLines, query);
        if (page.items().isEmpty()) {
            return "No matches found";
        }
        List<String> lines = page.items().stream()
            .map(line -> relativePrefix(line, context, false))
            .toList();
        String result = String.join("\n", lines);
        String limitInfo = limitInfo(page.appliedLimit(), query.offset());
        return limitInfo.isBlank() ? result : result + "\n\n[Showing results with pagination = " + limitInfo + "]";
    }

    private String formatCount(GrepQuery query, List<String> rawLines, ToolUseContext context) {
        Page page = page(rawLines, query);
        if (page.items().isEmpty()) {
            return "No matches found\n\nFound 0 total occurrences across 0 files.";
        }
        List<String> lines = new ArrayList<>();
        int matches = 0;
        int files = 0;
        for (String item : page.items()) {
            String line = relativePrefix(item, context, true);
            lines.add(line);
            int colon = line.lastIndexOf(':');
            if (colon > 0) {
                try {
                    matches += Integer.parseInt(line.substring(colon + 1));
                    files++;
                } catch (NumberFormatException ignored) {
                    // Ignore malformed count lines from rg.
                }
            }
        }
        StringBuilder output = new StringBuilder(String.join("\n", lines));
        output.append("\n\nFound ")
            .append(matches)
            .append(" total ")
            .append(matches == 1 ? "occurrence" : "occurrences")
            .append(" across ")
            .append(files)
            .append(' ')
            .append(files == 1 ? "file" : "files")
            .append('.');
        String limitInfo = limitInfo(page.appliedLimit(), query.offset());
        if (!limitInfo.isBlank()) {
            output.append(" with pagination = ").append(limitInfo);
        }
        return output.toString();
    }

    private Page page(List<String> rawLines, GrepQuery query) {
        List<String> lines = rawLines == null ? List.of() : rawLines;
        int offset = Math.min(query.offset(), lines.size());
        if (query.headLimit() != null && query.headLimit() == 0) {
            return new Page(lines.subList(offset, lines.size()), null);
        }
        int limit = query.headLimit() == null ? DEFAULT_HEAD_LIMIT : query.headLimit();
        int toIndex = Math.min(lines.size(), offset + limit);
        Integer appliedLimit = lines.size() - offset > limit ? limit : null;
        return new Page(lines.subList(offset, toIndex), appliedLimit);
    }

    private String limitInfo(Integer appliedLimit, int offset) {
        List<String> parts = new ArrayList<>();
        if (appliedLimit != null) {
            parts.add("limit: " + appliedLimit);
        }
        if (offset > 0) {
            parts.add("offset: " + offset);
        }
        return String.join(", ", parts);
    }

    private String relativePrefix(String line, ToolUseContext context, boolean lastColon) {
        int colon = lastColon ? line.lastIndexOf(':') : line.indexOf(':');
        if (colon <= 0) {
            return line;
        }
        return relativePath(line.substring(0, colon), context) + line.substring(colon);
    }

    private String relativePath(String rawPath, ToolUseContext context) {
        if (context == null || rawPath == null || rawPath.isBlank()) {
            return rawPath;
        }
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path path = Path.of(rawPath).toAbsolutePath().normalize();
        if (!path.startsWith(cwd)) {
            return rawPath.replace('\\', '/');
        }
        String relative = cwd.relativize(path).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    private record Page(List<String> items, Integer appliedLimit) {
    }
}
