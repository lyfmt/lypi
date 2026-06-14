package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RipgrepCommandBuilderTest {
    private final RipgrepCommandBuilder builder = new RipgrepCommandBuilder();

    @Test
    void buildsRipgrepArgumentsForDefaultFilesWithMatches() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle"));

        List<String> argv = builder.build(query);

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-l",
            "needle"
        ), argv);
    }

    @Test
    void contentModeAddsLineNumbersByDefault() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "output_mode", "content"));

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-n",
            "needle"
        ), builder.build(query));
    }

    @Test
    void contextTakesPrecedenceOverShortContextFlags() {
        GrepQuery query = GrepQuery.fromInput(Map.of(
            "pattern", "needle",
            "output_mode", "content",
            "context", 3,
            "-C", 2,
            "-A", 1,
            "-B", 1
        ));

        List<String> argv = builder.build(query);

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-n",
            "-C", "3",
            "needle"
        ), argv);
    }

    @Test
    void dashPatternUsesExplicitPatternFlag() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "-needle"));

        List<String> argv = builder.build(query);

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-l",
            "-e", "-needle"
        ), argv);
    }

    @Test
    void splitsGlobOnWhitespaceAndCommasButKeepsBraceGroups() {
        GrepQuery query = GrepQuery.fromInput(Map.of(
            "pattern", "needle",
            "glob", "*.java,*.md *.{ts,tsx}"
        ));

        List<String> argv = builder.build(query);

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-l",
            "needle",
            "--glob", "*.java",
            "--glob", "*.md",
            "--glob", "*.{ts,tsx}"
        ), argv);
    }

    @Test
    void typeCaseInsensitiveAndMultilineAddRipgrepFlags() {
        GrepQuery query = GrepQuery.fromInput(Map.of(
            "pattern", "needle",
            "type", "java",
            "-i", true,
            "multiline", true
        ));

        List<String> argv = builder.build(query);

        assertEquals(List.of(
            "--hidden",
            "--glob", "!.git",
            "--glob", "!.svn",
            "--glob", "!.hg",
            "--glob", "!.bzr",
            "--glob", "!.jj",
            "--glob", "!.sl",
            "--glob", "!target",
            "--glob", "!.lypi/sessions/**",
            "--glob", "!.lypi/cache/**",
            "--max-columns", "500",
            "-U",
            "--multiline-dotall",
            "-i",
            "-l",
            "needle",
            "--type", "java"
        ), argv);
    }

    @Test
    void maxResultsBackfillsHeadLimitWhenHeadLimitIsAbsent() {
        GrepQuery query = GrepQuery.fromInput(Map.of("pattern", "needle", "maxResults", 5));

        assertEquals(5, query.headLimit());
    }

    @Test
    void rejectsBlankPattern() {
        assertThrows(IllegalArgumentException.class, () -> GrepQuery.fromInput(Map.of("pattern", " ")));
    }
}
