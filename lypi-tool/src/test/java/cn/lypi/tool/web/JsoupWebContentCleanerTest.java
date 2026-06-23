package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

final class JsoupWebContentCleanerTest {
    @Test
    void extractsMainContentAndRemovesBoilerplate() {
        JsoupWebContentCleaner cleaner = new JsoupWebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult(
                "https://example.com/article",
                "text/html; charset=utf-8",
                """
                <html>
                  <head>
                    <title>Browser Title</title>
                    <style>.ad { display: block; }</style>
                    <script>console.log('secret');</script>
                  </head>
                  <body>
                    <nav>Navigation links</nav>
                    <main>
                      <article>
                        <h1>Article Title</h1>
                        <p>Lead paragraph with <a href="/docs">docs link</a>.</p>
                        <section hidden>Hidden section</section>
                        <div style="display:none">Invisible promo</div>
                        <h2>Details</h2>
                        <ul><li>First item</li><li>Second item</li></ul>
                      </article>
                    </main>
                    <footer>Footer links</footer>
                  </body>
                </html>
                """
            ),
            "markdown",
            Optional.empty(),
            10_000
        );

        assertEquals(Optional.of("Browser Title"), content.title());
        assertTrue(content.content().contains("# Article Title"));
        assertTrue(content.content().contains("Lead paragraph with docs link."));
        assertTrue(content.content().contains("## Details"));
        assertTrue(content.content().contains("- First item"));
        assertTrue(content.content().contains("- Second item"));
        assertFalse(content.content().contains("Navigation links"));
        assertFalse(content.content().contains("Footer links"));
        assertFalse(content.content().contains("secret"));
        assertFalse(content.content().contains("Invisible promo"));
        assertFalse(content.content().contains("Hidden section"));
    }

    @Test
    void queryFilterKeepsTitleAndMatchingBlocks() {
        JsoupWebContentCleaner cleaner = new JsoupWebContentCleaner();

        WebContentCleaner.CleanedContent content = cleaner.clean(
            new WebPageFetchResult(
                "https://example.com/article",
                "text/html",
                """
                <article>
                  <h1>Pricing Guide</h1>
                  <p>General overview.</p>
                  <p>Enterprise pricing starts at ten dollars.</p>
                  <p>Support contact.</p>
                </article>
                """
            ),
            "text",
            Optional.of("pricing"),
            10_000
        );

        assertTrue(content.content().contains("Pricing Guide"));
        assertTrue(content.content().contains("Enterprise pricing"));
        assertFalse(content.content().contains("General overview"));
        assertFalse(content.content().contains("Support contact"));
    }
}
