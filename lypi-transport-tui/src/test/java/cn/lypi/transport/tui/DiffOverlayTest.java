package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiffOverlayTest {
    @Test
    void rendersSummaryFilesAndPatch() {
        DiffOverlay overlay = new DiffOverlay(new DiffView(
            "2 files changed",
            List.of(
                new GitDiffFileView(Path.of("src/Main.java"), GitDiffStatus.MODIFIED, "Modified", Map.of()),
                new GitDiffFileView(Path.of("src/New.java"), GitDiffStatus.ADDED, "Added", Map.of())
            ),
            "diff --git a/src/Main.java b/src/Main.java\n-old\n+new",
            false,
            Map.of()
        ));

        assertEquals(List.of(
            "diff: 2 files changed",
            "M src/Main.java",
            "A src/New.java",
            "",
            "diff --git a/src/Main.java b/src/Main.java",
            "-old",
            "+new"
        ), overlay.lines());
    }
}
