package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class FileMentionPickerTest {
    @Test
    void filtersInjectedCandidatesFromAtTokenAndAcceptsQuotedPath() {
        FileMentionPicker picker = new FileMentionPicker(List.of(
            new FileMentionCandidate("src/Main.java", false),
            new FileMentionCandidate("docs/设计 文档.md", true)
        ));

        picker.updateDraft("open @设计", 8);

        assertEquals(List.of("docs/设计 文档.md"), picker.visiblePaths());
        assertEquals("open @\"docs/设计 文档.md\"", picker.accept().orElseThrow().draft());
    }

    @Test
    void escapePreservesDraftAndFilter() {
        FileMentionPicker picker = new FileMentionPicker(List.of(new FileMentionCandidate("README.md", false)));

        picker.updateDraft("cat @READ", 9);
        FileMentionState state = picker.cancel();

        assertEquals("cat @READ", state.draft());
        assertEquals("READ", state.filter());
    }
}
