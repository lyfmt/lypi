package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.tui.SessionResumeInfo;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResumeSessionSelectorTest {
    @Test
    void rendersThreadedCurrentFolderSessionListLikePi() {
        Path root = Path.of("/tmp/project/.lypi/sessions");
        ResumeSessionSelector selector = new ResumeSessionSelector(List.of(
            session(root.resolve("parent.jsonl"), "parent", Optional.empty(), "Parent question", 3, "2026-06-10T00:00:00Z"),
            session(root.resolve("child.jsonl"), "child", Optional.of(root.resolve("parent.jsonl")), "Child answer", 1, "2026-06-10T00:01:00Z")
        ), root.resolve("parent.jsonl"), 10);

        List<String> lines = selector.render(80);

        assertTrue(lines.get(0).contains("Resume Session (Current Folder)"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("›") && line.contains("Parent question")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("└─") && line.contains("Child answer")));
    }

    @Test
    void filtersByPhraseAndRegexLikePi() {
        ResumeSessionSelector selector = new ResumeSessionSelector(List.of(
            session(Path.of("/tmp/a.jsonl"), "a", Optional.empty(), "Node CVE", 1, "2026-06-10T00:00:00Z"),
            session(Path.of("/tmp/b.jsonl"), "b", Optional.empty(), "Bravery note", 1, "2026-06-10T00:01:00Z")
        ), Path.of("/tmp/a.jsonl"), 10);

        selector.acceptText("\"node cve\"");
        assertEquals(List.of("a"), selector.visibleSessionIds());

        selector.replaceSearch("re:\\bbrave\\b");
        assertEquals(List.of(), selector.visibleSessionIds());
    }

    @Test
    void scrollsAroundSelectionAndReturnsSelectedSession() {
        ResumeSessionSelector selector = new ResumeSessionSelector(List.of(
            session(Path.of("/tmp/1.jsonl"), "one", Optional.empty(), "one", 1, "2026-06-10T00:00:00Z"),
            session(Path.of("/tmp/2.jsonl"), "two", Optional.empty(), "two", 1, "2026-06-10T00:01:00Z"),
            session(Path.of("/tmp/3.jsonl"), "three", Optional.empty(), "three", 1, "2026-06-10T00:02:00Z"),
            session(Path.of("/tmp/4.jsonl"), "four", Optional.empty(), "four", 1, "2026-06-10T00:03:00Z")
        ), Path.of("/tmp/1.jsonl"), 2);

        selector.moveDown();
        selector.moveDown();

        assertEquals("two", selector.selectedSession().orElseThrow().sessionId());
        List<String> lines = selector.render(60);
        assertTrue(lines.stream().anyMatch(line -> line.contains("(3/4)")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("four")));
    }

    private SessionResumeInfo session(
        Path path,
        String id,
        Optional<Path> parent,
        String firstMessage,
        int messageCount,
        String modified
    ) {
        return new SessionResumeInfo(
            path,
            id,
            Path.of("/tmp/project"),
            parent,
            "leaf_" + id,
            Instant.parse("2026-06-09T00:00:00Z"),
            Instant.parse(modified),
            messageCount,
            firstMessage,
            firstMessage
        );
    }
}
