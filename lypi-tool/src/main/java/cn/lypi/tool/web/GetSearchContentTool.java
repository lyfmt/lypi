package cn.lypi.tool.web;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 从本地 Web 结果缓存取回完整内容。
 */
public final class GetSearchContentTool extends AbstractWebTool {
    private static final int DEFAULT_MAX_CHARS = 30_000;
    private static final int MAX_CHARS_LIMIT = 100_000;

    private final WebResultStore store;

    public GetSearchContentTool(WebResultStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    @Override
    public String name() {
        return "get_search_content";
    }

    @Override
    public String description() {
        return "Retrieve full content previously stored by web_search or web_fetch.";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "properties", Map.of(
                "responseId", Map.of("type", "string"),
                "url", Map.of("type", "string"),
                "urlIndex", Map.of("type", "integer", "minimum", 1),
                "query", Map.of("type", "string"),
                "queryIndex", Map.of("type", "integer", "minimum", 1),
                "maxChars", Map.of("type", "integer", "minimum", 1, "maximum", MAX_CHARS_LIMIT)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        try {
            maxChars(input);
            intInput(input, "urlIndex", 1, 1, Integer.MAX_VALUE);
            intInput(input, "queryIndex", 1, 1, Integer.MAX_VALUE);
            if (text(input, "responseId").isEmpty() && text(input, "query").isEmpty()) {
                return new ValidationResult(false, List.of("responseId 或 query 至少需要提供一个。"));
            }
            return new ValidationResult(true, List.of());
        } catch (RuntimeException exception) {
            return new ValidationResult(false, List.of(exception.getMessage()));
        }
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.TOOL_SPECIFIC,
            "get_search_content 只读取本地 Web 缓存。",
            Optional.<PermissionUpdate>empty(),
            Map.of("tool", name())
        );
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        try {
            Optional<WebStoredResult> result = resolveResult(input, context);
            if (result.isEmpty()) {
                return error(context, "未找到匹配的 Web 缓存结果。");
            }
            WebStoredResult storedResult = result.orElseThrow();
            Optional<WebStoredItem> item = selectItem(storedResult, input);
            if (item.isEmpty()) {
                return error(context, "未找到匹配的 URL 内容。");
            }
            return success(context, render(storedResult, item.orElseThrow(), maxChars(input)));
        } catch (RuntimeException exception) {
            return error(context, "Web 内容取回失败: " + exception.getMessage());
        }
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "get_search_content " + input;
    }

    private Optional<WebStoredResult> resolveResult(Map<String, Object> input, ToolUseContext context) {
        Optional<String> responseId = text(input, "responseId");
        if (responseId.isPresent()) {
            return store.findByResponseId(context.sessionId(), responseId.orElseThrow());
        }
        Optional<String> query = text(input, "query");
        if (query.isPresent()) {
            return store.findLatestByQuery(context.sessionId(), query.orElseThrow());
        }
        return Optional.empty();
    }

    private Optional<WebStoredItem> selectItem(WebStoredResult result, Map<String, Object> input) {
        Optional<String> requestedUrl = text(input, "url").map(GetSearchContentTool::normalizeUrl);
        if (requestedUrl.isPresent()) {
            String url = requestedUrl.orElseThrow();
            return result.items().stream()
                .filter(item -> normalizeUrl(item.url()).equals(url))
                .findFirst();
        }
        int index = input.containsKey("query") && !input.containsKey("urlIndex")
            ? intInput(input, "queryIndex", 1, 1, Integer.MAX_VALUE)
            : intInput(input, "urlIndex", 1, 1, Integer.MAX_VALUE);
        int itemIndex = index - 1;
        if (itemIndex < 0 || itemIndex >= result.items().size()) {
            return Optional.empty();
        }
        return Optional.of(result.items().get(itemIndex));
    }

    private String render(WebStoredResult result, WebStoredItem item, int maxChars) {
        String content = item.content();
        boolean truncated = item.truncated();
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars);
            truncated = true;
        }
        StringBuilder builder = new StringBuilder();
        builder.append("responseId=").append(result.responseId());
        builder.append("\nsourceTool=").append(result.sourceTool());
        builder.append("\nurl=").append(item.url());
        item.title().ifPresent(title -> builder.append("\ntitle=").append(title));
        item.format().ifPresent(format -> builder.append("\nformat=").append(format));
        builder.append("\ntruncated=").append(truncated);
        builder.append("\ncontent:\n").append(content);
        if (content.isBlank() && item.snippet().isPresent()) {
            builder.append("\nsnippet:\n").append(item.snippet().orElseThrow());
            builder.append("\nnote=该搜索结果没有完整正文，可用 web_fetch 拉取 URL。");
        }
        return builder.toString();
    }

    private int maxChars(Map<String, Object> input) {
        return intInput(input, "maxChars", DEFAULT_MAX_CHARS, 1, MAX_CHARS_LIMIT);
    }

    private int intInput(Map<String, Object> input, String fieldName, int defaultValue, int min, int max) {
        Object value = input.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        int parsed;
        if (value instanceof Number number) {
            parsed = number.intValue();
        } else {
            try {
                parsed = Integer.parseInt(value.toString());
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(fieldName + " 必须是整数。");
            }
        }
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException(fieldName + " 超出范围。");
        }
        return parsed;
    }

    private Optional<String> text(Map<String, Object> input, String fieldName) {
        Object value = input.get(fieldName);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString().trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static String normalizeUrl(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
