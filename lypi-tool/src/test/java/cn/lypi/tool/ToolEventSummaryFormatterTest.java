package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ToolEventSummaryFormatterTest {
    private final ToolEventSummaryFormatter formatter = new ToolEventSummaryFormatter();

    @Test
    void normalizesAndTruncatesRenderedInputAtUnicodeCodePointBoundary() {
        String rendered = "bash printf 'one\ntwo'\r\nthen-more-content\u0000 " + "🙂".repeat(200);

        String summary = formatter.inputSummary("bash", rendered, Map.of("command", rendered));

        assertTrue(summary.startsWith("bash printf 'one two' then-more-content"));
        assertFalse(summary.contains("\r"));
        assertFalse(summary.contains("\n"));
        assertFalse(summary.contains("\u0000"));
        assertFalse(summary.contains("  "));
        assertTrue(summary.endsWith("🙂…"));
        assertTrue(summary.codePointCount(0, summary.length()) <= ToolEventSummaryFormatter.INPUT_MAX_CODE_POINTS);
        assertEquals(1L, summary.codePoints().filter(codePoint -> codePoint == '…').count());
    }

    @Test
    void summarizesOnlyThreeDeterministicallySortedFieldsForUnknownTools() {
        String content = "TOP-SECRET" + "x".repeat(1_048_576 - "TOP-SECRET".length());
        Map<String, Object> input = Map.of(
            "zzMode", "safe",
            "nested", Map.of("first", 1, "second", 2),
            "content", content,
            "path", "README.md",
            "zzItems", java.util.List.of("a", "b", "c"),
            "zzEnabled", true
        );

        String summary = formatter.inputSummary("mystery", null, input);

        assertEquals("mystery content=<1048576 chars> nested=<2 fields> path=README.md", summary);
        assertFalse(summary.contains("TOP-SECRET"));
        assertFalse(summary.contains("zzEnabled"));
        assertFalse(summary.contains("zzItems"));
        assertFalse(summary.contains("zzMode"));
        assertFalse(summary.contains("{"));
    }

    @Test
    void boundsMultilineResultSummaryAndPreviewWhileReportingHiddenLines() {
        String output = IntStream.rangeClosed(1, 20)
            .mapToObj(line -> "line-" + line + " " + "🙂".repeat(20))
            .collect(Collectors.joining("\r\n"))
            + "x".repeat(300);

        String summary = formatter.resultSummary(output);
        String preview = formatter.preview(output);

        assertSingleLineWithHiddenLineCount(summary, ToolEventSummaryFormatter.RESULT_MAX_CODE_POINTS);
        assertSingleLineWithHiddenLineCount(preview, ToolEventSummaryFormatter.PREVIEW_MAX_CODE_POINTS);
    }

    private void assertSingleLineWithHiddenLineCount(String value, int maxCodePoints) {
        assertTrue(value.startsWith("line-1"));
        assertFalse(value.contains("\r"));
        assertFalse(value.contains("\n"));
        assertTrue(value.endsWith("(+19 lines)"));
        assertTrue(value.codePointCount(0, value.length()) <= maxCodePoints);
    }
}
