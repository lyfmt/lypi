package cn.lypi.tool.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 解析 Web 工具输入。
 */
public final class WebToolInputs {
    private static final int DEFAULT_SEARCH_RESULTS = 5;
    private static final int MAX_SEARCH_RESULTS = 10;
    private static final int DEFAULT_FETCH_CHARS = 12_000;
    private static final int MAX_FETCH_CHARS = 50_000;
    private static final List<String> PROVIDERS = List.of("tavily", "brave", "perplexity");
    private static final List<String> FETCH_FORMATS = List.of("markdown", "text");

    private WebToolInputs() {
    }

    /**
     * 解析 `web_search` 输入。
     */
    public static WebSearchRequest search(Map<String, Object> input) {
        String query = requiredString(input, "query");
        return new WebSearchRequest(
            query,
            intInput(input, "maxResults", DEFAULT_SEARCH_RESULTS, 1, MAX_SEARCH_RESULTS),
            domainList(input, "allowedDomains"),
            domainList(input, "blockedDomains"),
            optionalString(input, "recency"),
            optionalString(input, "country"),
            optionalString(input, "language"),
            optionalProvider(input),
            booleanInput(input, "includeAnswer", false)
        );
    }

    /**
     * 解析 `web_fetch` 输入。
     */
    public static WebFetchRequest fetch(Map<String, Object> input) {
        String format = optionalString(input, "format").orElse("markdown").toLowerCase(Locale.ROOT);
        if (!FETCH_FORMATS.contains(format)) {
            throw new IllegalArgumentException("format 只支持 markdown 或 text。");
        }
        return new WebFetchRequest(
            requiredString(input, "url"),
            optionalString(input, "query"),
            format,
            intInput(input, "maxChars", DEFAULT_FETCH_CHARS, 1, MAX_FETCH_CHARS),
            optionalProvider(input)
        );
    }

    private static String requiredString(Map<String, Object> input, String fieldName) {
        Optional<String> value = optionalString(input, fieldName);
        if (value.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " 不能为空。");
        }
        return value.orElseThrow();
    }

    private static Optional<String> optionalString(Map<String, Object> input, String fieldName) {
        Object value = input.get(fieldName);
        if (value == null) {
            return Optional.empty();
        }
        String text = value.toString().trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static int intInput(Map<String, Object> input, String fieldName, int defaultValue, int min, int max) {
        Object value = input.get(fieldName);
        int parsed = switch (value) {
            case null -> defaultValue;
            case Number number -> number.intValue();
            default -> Integer.parseInt(value.toString());
        };
        return Math.max(min, Math.min(max, parsed));
    }

    private static boolean booleanInput(Map<String, Object> input, String fieldName, boolean defaultValue) {
        Object value = input.get(fieldName);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    private static Optional<String> optionalProvider(Map<String, Object> input) {
        Optional<String> provider = optionalString(input, "provider")
            .map(value -> value.toLowerCase(Locale.ROOT));
        if (provider.isPresent() && !PROVIDERS.contains(provider.orElseThrow())) {
            throw new IllegalArgumentException("provider 只支持 tavily、brave 或 perplexity。");
        }
        return provider;
    }

    private static List<String> domainList(Map<String, Object> input, String fieldName) {
        Object value = input.get(fieldName);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(fieldName + " 必须是字符串数组。");
        }
        LinkedHashSet<String> domains = new LinkedHashSet<>();
        for (Object item : values) {
            if (!(item instanceof String raw)) {
                throw new IllegalArgumentException(fieldName + " 必须是字符串数组。");
            }
            String domain = raw.trim().toLowerCase(Locale.ROOT);
            if (!domain.isBlank()) {
                domains.add(domain);
            }
        }
        return new ArrayList<>(domains);
    }
}
