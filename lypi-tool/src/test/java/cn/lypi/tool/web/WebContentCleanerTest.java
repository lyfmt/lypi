package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class WebContentCleanerTest {
    @Test
    void cleansHtmlToMarkdown() {
        WebContentCleaner cleaner = new WebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult(
                "https://example.com/doc",
                "text/html; charset=utf-8",
                """
                <html>
                  <head>
                    <title>Example Title</title>
                    <style>body { color: red; }</style>
                    <script>alert('x');</script>
                  </head>
                  <body>
                    <!-- comment -->
                    <h1>Example Heading</h1>
                    <p>First paragraph.</p>
                    <ul><li>One</li><li>Two</li></ul>
                    <noscript>hidden</noscript>
                  </body>
                </html>
                """
            ),
            "markdown",
            Optional.empty(),
            10_000
        );

        assertEquals(Optional.of("Example Title"), content.title());
        assertTrue(content.content().contains("# Example Heading"));
        assertTrue(content.content().contains("First paragraph."));
        assertTrue(content.content().contains("- One"));
        assertTrue(content.content().contains("- Two"));
        assertFalse(content.content().contains("alert"));
        assertFalse(content.content().contains("color: red"));
        assertFalse(content.content().contains("comment"));
        assertFalse(content.content().contains("hidden"));
    }

    @Test
    void cleansHtmlToPlainTextAndNormalizesControlCharacters() {
        WebContentCleaner cleaner = new WebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult(
                "https://example.com/doc",
                "text/html",
                "<h1>Title</h1><p>A\u0000    B</p><p>C</p>"
            ),
            "text",
            Optional.empty(),
            10_000
        );

        assertEquals("Title\n\nA B\n\nC", content.content());
    }

    @Test
    void queryKeepsMatchingParagraphsAndTitle() {
        WebContentCleaner cleaner = new WebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult(
                "https://example.com/doc",
                "text/html",
                """
                <h1>Product</h1>
                <p>General overview.</p>
                <p>Pricing starts at ten dollars.</p>
                <p>Support contact.</p>
                """
            ),
            "text",
            Optional.of("pricing"),
            10_000
        );

        assertTrue(content.content().contains("Product"));
        assertTrue(content.content().contains("Pricing starts"));
        assertFalse(content.content().contains("General overview"));
        assertFalse(content.content().contains("Support contact"));
    }

    @Test
    void truncatesAfterCleaning() {
        WebContentCleaner cleaner = new WebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult("https://example.com/doc", "text/plain", "1234567890"),
            "text",
            Optional.empty(),
            4
        );

        assertEquals("1234", content.content());
    }
}
