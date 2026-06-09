package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class MarkdownRendererTest {
    @Test
    void rendersHeadingsListsBlockquotesAndRulesWithoutMarkdownNoise() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderer.render("""
            ## Title ##
            - [x] done
            1. next
            > quoted text wraps
            ---
            """, 14);

        assertEquals("Title", lines.get(0));
        assertEquals("[x] done", lines.get(1));
        assertEquals("1. next", lines.get(2));
        assertEquals("| quoted text", lines.get(3));
        assertEquals("| wraps", lines.get(4));
        assertEquals("──────────────", lines.get(5));
    }

    @Test
    void rendersFencedDiffCodeAndInlineFormatting() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderer.render("""
            ```diff
            + added
            - removed
            ```
            `code` **bold** *em* ~~gone~~ [same](https://a.test) [same](https://a.test)
            """, 80);

        assertEquals("\033[32m+ added\033[0m", lines.get(0));
        assertEquals("\033[31m- removed\033[0m", lines.get(1));
        assertEquals("code bold em gone same https://a.test", lines.get(2));
    }

    @Test
    void keepsEscapedMarkdownMarkersAndShrinksCjkPipeTables() {
        MarkdownRenderer renderer = new MarkdownRenderer();

        List<String> lines = renderer.render("""
            \\*literal\\*
            | 名称 | 状态 |
            | --- | --- |
            | 测试文件 | 成功 |
            """, 16);

        assertEquals("*literal*", lines.get(0));
        assertTrue(lines.get(1).startsWith("名称"));
        assertTrue(AnsiWidth.displayWidth(lines.get(1)) <= 16);
        assertTrue(AnsiWidth.displayWidth(lines.get(2)) <= 16);
    }
}
