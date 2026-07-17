package cn.lypi.tool;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ToolEventSummaryFormatter {
    static final int INPUT_MAX_CODE_POINTS = 160;
    static final int RESULT_MAX_CODE_POINTS = 200;
    static final int PREVIEW_MAX_CODE_POINTS = 80;

    private static final int MAX_INPUT_FIELDS = 3;
    private static final int MAX_SCALAR_CODE_POINTS = 64;
    private static final Pattern LINE_BREAK = Pattern.compile("\\R");

    ToolEventSummaryFormatter() {
    }

    public static String genericInputSummary(String toolName, Map<String, Object> input) {
        return new ToolEventSummaryFormatter().inputSummary(toolName, null, input);
    }

    String inputSummary(String toolName, String renderedForUser, Map<String, Object> input) {
        String rendered = normalizeSingleLine(renderedForUser);
        String candidate = rendered.isEmpty() ? buildGenericInputSummary(toolName, input) : rendered;
        return truncate(candidate, INPUT_MAX_CODE_POINTS);
    }

    String resultSummary(String outputText) {
        return outputSummary(outputText, RESULT_MAX_CODE_POINTS);
    }

    String preview(String outputText) {
        return outputSummary(outputText, PREVIEW_MAX_CODE_POINTS);
    }

    private String buildGenericInputSummary(String toolName, Map<String, Object> input) {
        String normalizedToolName = normalizeSingleLine(toolName);
        StringBuilder summary = new StringBuilder(normalizedToolName.isEmpty() ? "tool" : normalizedToolName);
        if (input == null || input.isEmpty()) {
            return summary.toString();
        }
        List<String> keys = input.keySet().stream()
            .filter(Objects::nonNull)
            .sorted()
            .limit(MAX_INPUT_FIELDS)
            .toList();
        for (String key : keys) {
            summary.append(' ')
                .append(normalizeSingleLine(key))
                .append('=')
                .append(displayValue(input.get(key)));
        }
        return normalizeSingleLine(summary.toString());
    }

    private String displayValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String text) {
            int codePoints = codePointCount(text);
            return codePoints <= MAX_SCALAR_CODE_POINTS
                ? normalizeSingleLine(text)
                : "<" + codePoints + " chars>";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map<?, ?> map) {
            return "<" + map.size() + " fields>";
        }
        if (value instanceof Collection<?> collection) {
            return "<" + collection.size() + " items>";
        }
        if (value.getClass().isArray()) {
            return "<" + Array.getLength(value) + " items>";
        }
        return "<" + value.getClass().getSimpleName() + ">";
    }

    private String outputSummary(String outputText, int maxCodePoints) {
        String normalized = normalizeSingleLine(outputText);
        int hiddenLines = lineBreakCount(outputText);
        if (hiddenLines == 0) {
            return truncate(normalized, maxCodePoints);
        }
        String suffix = " (+" + hiddenLines + " lines)";
        if (codePointCount(normalized) + codePointCount(suffix) <= maxCodePoints) {
            return normalized + suffix;
        }
        int available = maxCodePoints - codePointCount(suffix) - 1;
        if (available <= 0) {
            return truncate(suffix.strip(), maxCodePoints);
        }
        return prefix(normalized, available) + "…" + suffix;
    }

    private String normalizeSingleLine(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)
                || Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)) {
                pendingSpace = normalized.length() > 0;
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                pendingSpace = false;
            }
            normalized.appendCodePoint(codePoint);
        }
        return normalized.toString();
    }

    private String truncate(String value, int maxCodePoints) {
        if (value == null || maxCodePoints <= 0) {
            return "";
        }
        if (codePointCount(value) <= maxCodePoints) {
            return value;
        }
        if (maxCodePoints == 1) {
            return "…";
        }
        return prefix(value, maxCodePoints - 1) + "…";
    }

    private String prefix(String value, int codePoints) {
        if (value == null || value.isEmpty() || codePoints <= 0) {
            return "";
        }
        int count = Math.min(codePoints, codePointCount(value));
        return value.substring(0, value.offsetByCodePoints(0, count));
    }

    private int lineBreakCount(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        int count = 0;
        Matcher matcher = LINE_BREAK.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private int codePointCount(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }
}
