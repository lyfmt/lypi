package cn.lypi.agent;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolCallMapper {
    public List<ToolUseRequest> requestsFrom(AgentMessage assistant) {
        List<ToolUseRequest> requests = new ArrayList<>();
        for (cn.lypi.contracts.context.ContentBlock block : assistant.content()) {
            if (block instanceof ToolCallContentBlock toolCall) {
                requests.add(new ToolUseRequest(
                    toolCall.toolUseId(),
                    toolCall.toolName(),
                    parseObject(toolCall.text()),
                    assistant.id()
                ));
            }
        }
        return List.copyOf(requests);
    }

    private Map<String, Object> parseObject(String text) {
        String source = text == null ? "" : text.trim();
        if (source.isEmpty() || source.equals("{}")) {
            return Map.of();
        }
        if (!source.startsWith("{") || !source.endsWith("}")) {
            return Map.of("input", source);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (String pair : splitPairs(source.substring(1, source.length() - 1))) {
            if (pair.isBlank()) {
                continue;
            }
            int separator = findSeparator(pair);
            if (separator < 0) {
                continue;
            }
            String key = unquote(pair.substring(0, separator).trim());
            String rawValue = pair.substring(separator + 1).trim();
            values.put(key, parseValue(rawValue));
        }
        return Map.copyOf(values);
    }

    private List<String> splitPairs(String source) {
        List<String> pairs = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        boolean escaping = false;
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            if (escaping) {
                current.append(ch);
                escaping = false;
            } else if (ch == '\\') {
                current.append(ch);
                escaping = true;
            } else if (ch == '"') {
                current.append(ch);
                inString = !inString;
            } else if (ch == ',' && !inString) {
                pairs.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        pairs.add(current.toString());
        return pairs;
    }

    private int findSeparator(String pair) {
        boolean inString = false;
        boolean escaping = false;
        for (int index = 0; index < pair.length(); index++) {
            char ch = pair.charAt(index);
            if (escaping) {
                escaping = false;
            } else if (ch == '\\') {
                escaping = true;
            } else if (ch == '"') {
                inString = !inString;
            } else if (ch == ':' && !inString) {
                return index;
            }
        }
        return -1;
    }

    private Object parseValue(String rawValue) {
        if (rawValue.equals("null")) {
            return null;
        }
        if (rawValue.equals("true") || rawValue.equals("false")) {
            return Boolean.valueOf(rawValue);
        }
        if (rawValue.startsWith("\"") && rawValue.endsWith("\"")) {
            return unquote(rawValue);
        }
        try {
            return Long.parseLong(rawValue);
        } catch (NumberFormatException ignored) {
            return rawValue;
        }
    }

    private String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
        }
        return trimmed;
    }
}
