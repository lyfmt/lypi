package cn.lypi.tool.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 对本地抓取的网页内容做基础清洗。
 */
public final class WebContentCleaner {
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
        "(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>"
    );
    private static final Pattern COMMENTS = Pattern.compile("(?is)<!--.*?-->");
    private static final Pattern TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern TAGS = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\r\n\t]]");
    private static final Pattern BLANK_LINES = Pattern.compile("(?m)(?:\\s*\\R){3,}");

    /**
     * 清洗抓取结果。
     */
    public CleanedContent clean(
        WebPageFetchResult result,
        String format,
        Optional<String> query,
        int maxChars
    ) {
        String normalizedFormat = "text".equalsIgnoreCase(format) ? "text" : "markdown";
        String body = result == null ? "" : result.body();
        Optional<String> title = htmlTitle(body);
        String content = isHtml(result == null ? "" : result.contentType(), body)
            ? htmlToText(body, normalizedFormat)
            : normalizeText(body);
        content = applyQueryFilter(content, query);
        content = truncate(content, maxChars);
        return new CleanedContent(title, content);
    }

    private Optional<String> htmlTitle(String html) {
        java.util.regex.Matcher matcher = TITLE.matcher(html == null ? "" : html);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String title = normalizeInline(decodeEntities(matcher.group(1)));
        return title.isBlank() ? Optional.empty() : Optional.of(title);
    }

    private boolean isHtml(String contentType, String body) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("html") || (body != null && body.toLowerCase(Locale.ROOT).contains("<html"));
    }

    private String htmlToText(String html, String format) {
        String value = html == null ? "" : html;
        value = SCRIPT_STYLE.matcher(value).replaceAll("");
        value = COMMENTS.matcher(value).replaceAll("");
        value = value.replaceAll("(?is)<title[^>]*>.*?</title>", "");
        value = value.replaceAll("(?is)<br\\s*/?>", "\n");
        value = value.replaceAll("(?is)</p\\s*>", "\n\n");
        value = value.replaceAll("(?is)<p[^>]*>", "");
        value = value.replaceAll("(?is)</(div|section|article|header|main|footer|blockquote)\\s*>", "\n\n");
        value = value.replaceAll("(?is)<(div|section|article|header|main|footer|blockquote)[^>]*>", "");
        if ("markdown".equals(format)) {
            value = markdownHeadingsAndLists(value);
        } else {
            value = value.replaceAll("(?is)<h[1-6][^>]*>", "\n\n");
            value = value.replaceAll("(?is)</h[1-6]\\s*>", "\n\n");
            value = value.replaceAll("(?is)<li[^>]*>", "\n");
            value = value.replaceAll("(?is)</li\\s*>", "\n");
        }
        value = value.replaceAll("(?is)</?(ul|ol)\\s*[^>]*>", "\n");
        value = TAGS.matcher(value).replaceAll("");
        return normalizeText(decodeEntities(value));
    }

    private String markdownHeadingsAndLists(String value) {
        String text = value;
        text = text.replaceAll("(?is)<h1[^>]*>", "\n\n# ");
        text = text.replaceAll("(?is)</h1\\s*>", "\n\n");
        text = text.replaceAll("(?is)<h2[^>]*>", "\n\n## ");
        text = text.replaceAll("(?is)</h2\\s*>", "\n\n");
        text = text.replaceAll("(?is)<h3[^>]*>", "\n\n### ");
        text = text.replaceAll("(?is)</h3\\s*>", "\n\n");
        text = text.replaceAll("(?is)<h[4-6][^>]*>", "\n\n#### ");
        text = text.replaceAll("(?is)</h[4-6]\\s*>", "\n\n");
        text = text.replaceAll("(?is)<li[^>]*>", "\n- ");
        text = text.replaceAll("(?is)</li\\s*>", "\n");
        return text;
    }

    private String normalizeText(String value) {
        String text = CONTROL.matcher(value == null ? "" : value).replaceAll("");
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        for (String line : text.split("\\n", -1)) {
            String normalized = normalizeInline(line);
            if (normalized.isBlank()) {
                builder.append('\n');
            } else {
                builder.append(normalized).append('\n');
            }
        }
        String compacted = BLANK_LINES.matcher(builder.toString().trim()).replaceAll("\n\n");
        return compacted.trim();
    }

    private String normalizeInline(String value) {
        return value == null ? "" : value.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
    }

    private String decodeEntities(String value) {
        return value == null ? "" : value
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private String applyQueryFilter(String content, Optional<String> query) {
        Optional<String> normalizedQuery = query == null ? Optional.empty() : query
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .map(value -> value.toLowerCase(Locale.ROOT));
        if (normalizedQuery.isEmpty()) {
            return content;
        }
        String[] blocks = content.split("\\n\\n+");
        List<String> kept = new ArrayList<>();
        if (blocks.length > 0 && isHeading(blocks[0])) {
            kept.add(blocks[0]);
        }
        for (String block : blocks) {
            if (block.toLowerCase(Locale.ROOT).contains(normalizedQuery.orElseThrow()) && !kept.contains(block)) {
                kept.add(block);
            }
        }
        return kept.isEmpty() ? content : String.join("\n\n", kept);
    }

    private boolean isHeading(String block) {
        String value = block == null ? "" : block.trim();
        return value.startsWith("#") || (!value.contains(".") && value.length() <= 120);
    }

    private String truncate(String value, int maxChars) {
        int limit = Math.max(1, maxChars);
        return value.length() > limit ? value.substring(0, limit) : value;
    }

    /**
     * 清洗后的内容。
     */
    public record CleanedContent(Optional<String> title, String content) {
        public CleanedContent {
            title = title == null ? Optional.empty() : title;
            content = content == null ? "" : content;
        }
    }
}
