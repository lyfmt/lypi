package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tool.ToolUseContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrepResultFormatterTest {
    @TempDir
    Path tempDir;

    private final GrepResultFormatter formatter = new GrepResultFormatter();

    @Test
    void filesWithMatchesReportsRelativeFiles() throws Exception {
        Path first = writeFile("a.java");
        Path second = writeFile("b.java");
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));

        String output = formatter.format(query, List.of(first.toString(), second.toString()), context());

        assertEquals("Found 2 files\na.java\nb.java", output);
    }

    @Test
    void filesWithMatchesSortsByModifiedTimeDescendingThenName() throws Exception {
        Path older = writeFile("a.java");
        Path newer = writeFile("b.java");
        Path sameTimeEarlier = writeFile("c.java");
        Path sameTimeLater = writeFile("d.java");
        Files.setLastModifiedTime(older, FileTime.from(Instant.parse("2026-06-14T00:00:00Z")));
        Files.setLastModifiedTime(newer, FileTime.from(Instant.parse("2026-06-14T00:00:02Z")));
        Files.setLastModifiedTime(sameTimeEarlier, FileTime.from(Instant.parse("2026-06-14T00:00:01Z")));
        Files.setLastModifiedTime(sameTimeLater, FileTime.from(Instant.parse("2026-06-14T00:00:01Z")));
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));

        String output = formatter.format(query, List.of(older.toString(), newer.toString(), sameTimeLater.toString(), sameTimeEarlier.toString()), context());

        assertEquals("Found 4 files\nb.java\nc.java\nd.java\na.java", output);
    }

    @Test
    void filesWithMatchesReportsNoFiles() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));

        assertEquals("No files found", formatter.format(query, List.of(), context()));
    }

    @Test
    void contentModeRelativizesFilePrefixes() throws Exception {
        Path file = writeFile("src/App.java");
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "output_mode", "content"));

        String output = formatter.format(query, List.of(file + ":7:needle"), context());

        assertEquals("src/App.java:7:needle", output);
    }

    @Test
    void countModeAddsSummary() throws Exception {
        Path first = writeFile("a.java");
        Path second = writeFile("b.java");
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "output_mode", "count"));

        String output = formatter.format(query, List.of(first + ":2", second + ":3"), context());

        assertTrue(output.contains("a.java:2"));
        assertTrue(output.contains("b.java:3"));
        assertTrue(output.contains("Found 5 total occurrences across 2 files."));
    }

    @Test
    void appliesDefaultHeadLimitOnlyWhenTruncated() throws Exception {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));
        List<String> files = java.util.stream.IntStream.rangeClosed(1, 251)
            .mapToObj(index -> tempDir.resolve("file" + index + ".java").toString())
            .toList();

        String output = formatter.format(query, files, context());

        assertTrue(output.contains("Found 250 files limit: 250"));
        assertEquals(251, output.lines().count());
    }

    @Test
    void headLimitZeroDisablesTruncation() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "head_limit", 0));

        String output = formatter.format(query, List.of("one.java", "two.java"), context());

        assertEquals("Found 2 files\none.java\ntwo.java", output);
    }

    @Test
    void offsetSkipsEntriesAndReportsPagination() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "head_limit", 1, "offset", 1));

        String output = formatter.format(query, List.of("one.java", "two.java", "three.java"), context());

        assertEquals("Found 1 file limit: 1, offset: 1\nthree.java", output);
    }

    private Path writeFile(String relativePath) throws Exception {
        Path path = tempDir.resolve(relativePath);
        Files.createDirectories(path.getParent() == null ? tempDir : path.getParent());
        Files.writeString(path, "needle");
        return path;
    }

    private ToolUseContext context() {
        return new ToolUseContext("ses_1", "msg_1", tempDir, Map.of());
    }
}
