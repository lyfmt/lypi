package cn.lypi.tool.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;

/**
 * 使用 jsoup 解析 HTML 并抽取主体正文。
 */
public final class JsoupWebContentCleaner {
    private static final int DEFAULT_MAX_CHARS = 50_000;

    private final WebContentCleaner fallbackCleaner = new WebContentCleaner();

    /**
     * 清洗抓取结果。
     */
    public WebContentCleaner.CleanedContent clean(
        WebPageFetchResult result,
        String format,
        Optional<String> query,
        int maxChars
    ) {
        String normalizedFormat = "text".equalsIgnoreCase(format) ? "text" : "markdown";
        if (!isHtml(result == null ? "" : result.contentType(), result == null ? "" : result.body())) {
            return fallbackCleaner.clean(result, normalizedFormat, query, maxChars);
        }
        try {
            Document document = Jsoup.parse(result == null ? "" : result.body(), result == null ? "" : result.finalUrl());
            Optional<String> title = title(document);
            removeBoilerplate(document);
            Element main = mainElement(document);
            String content = renderChildren(main, normalizedFormat);
            content = normalizeText(content);
            content = applyQueryFilter(content, query);
            content = truncate(content, maxChars);
            return new WebContentCleaner.CleanedContent(title, content);
        } catch (RuntimeException exception) {
            return fallbackCleaner.clean(result, normalizedFormat, query, maxChars);
        }
    }

    private boolean isHtml(String contentType, String body) {
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return normalized.contains("html") || (body != null && body.toLowerCase(Locale.ROOT).contains("<html"));
    }

    private Optional<String> title(Document document) {
        String title = document == null ? "" : document.title().trim();
        return title.isBlank() ? Optional.empty() : Optional.of(title);
    }

    private void removeBoilerplate(Document document) {
        document.select("script,style,noscript,template,nav,footer,aside,[hidden], [aria-hidden=true]").remove();
        document.select("[style]").stream()
            .filter(element -> element.attr("style").toLowerCase(Locale.ROOT).contains("display:none"))
            .forEach(Element::remove);
    }

    private Element mainElement(Document document) {
        Elements candidates = document.select("article, main, [role=main]");
        if (!candidates.isEmpty()) {
            return candidates.stream()
                .max(java.util.Comparator.comparingInt(element -> element.text().length()))
                .orElse(candidates.first());
        }
        return document.body() == null ? document : document.body();
    }

    private String renderChildren(Element root, String format) {
        StringBuilder builder = new StringBuilder();
        for (Node child : root.childNodes()) {
            renderNode(child, format, builder);
        }
        return builder.toString();
    }

    private void renderNode(Node node, String format, StringBuilder builder) {
        if (node instanceof TextNode textNode) {
            appendInline(builder, textNode.text());
            return;
        }
        if (!(node instanceof Element element)) {
            return;
        }
        String tag = element.normalName();
        switch (tag) {
            case "h1" -> appendBlock(builder, heading(element, format, "# "));
            case "h2" -> appendBlock(builder, heading(element, format, "## "));
            case "h3" -> appendBlock(builder, heading(element, format, "### "));
            case "h4", "h5", "h6" -> appendBlock(builder, heading(element, format, "#### "));
            case "p", "div", "section", "article", "main", "blockquote" -> appendBlock(builder, renderElementChildren(element, format));
            case "li" -> appendBlock(builder, "markdown".equals(format) ? "- " + element.text() : element.text());
            case "ul", "ol" -> element.childNodes().forEach(child -> renderNode(child, format, builder));
            case "br" -> builder.append('\n');
            default -> {
                String text = renderElementChildren(element, format);
                if (blockTag(tag)) {
                    appendBlock(builder, text);
                } else {
                    appendInline(builder, text);
                }
            }
        }
    }

    private String heading(Element element, String format, String prefix) {
        return "markdown".equals(format) ? prefix + element.text() : element.text();
    }

    private String renderElementChildren(Element element, String format) {
        StringBuilder builder = new StringBuilder();
        for (Node child : element.childNodes()) {
            renderNode(child, format, builder);
        }
        String rendered = normalizeInline(builder.toString());
        return rendered.isBlank() ? element.text() : rendered;
    }

    private boolean blockTag(String tag) {
        return List.of("header", "table", "tr", "td", "pre").contains(tag);
    }

    private void appendBlock(StringBuilder builder, String text) {
        String normalized = normalizeInline(text);
        if (normalized.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append("\n\n");
        }
        builder.append(normalized);
    }

    private void appendInline(StringBuilder builder, String text) {
        String normalized = normalizeInline(text);
        if (normalized.isBlank()) {
            return;
        }
        if (!builder.isEmpty()) {
            char last = builder.charAt(builder.length() - 1);
            if (!Character.isWhitespace(last) && last != '(' && last != '[' && !startsWithPunctuation(normalized)) {
                builder.append(' ');
            }
        }
        builder.append(normalized);
    }

    private boolean startsWithPunctuation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        char first = value.charAt(0);
        return ".,;:!?)]}".indexOf(first) >= 0;
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

    private String normalizeText(String value) {
        String text = value == null ? "" : value.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("[\\p{Cntrl}&&[^\r\n\t]]", "");
        text = text.replaceAll("(?m)(?:\\s*\\R){3,}", "\n\n");
        return text.trim();
    }

    private String normalizeInline(String value) {
        return value == null ? "" : value.replaceAll("[ \\t\\x0B\\f]+", " ").trim();
    }

    private String truncate(String value, int maxChars) {
        int limit = maxChars <= 0 ? DEFAULT_MAX_CHARS : maxChars;
        return value.length() > limit ? value.substring(0, limit) : value;
    }
}
