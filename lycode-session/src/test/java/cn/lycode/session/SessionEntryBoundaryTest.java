package cn.lycode.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.memory.MemoryWriteEntry;
import cn.lycode.contracts.session.SessionEntry;
import cn.lycode.contracts.session.ShellState;
import cn.lycode.contracts.session.ShellStateChangeEntry;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SessionEntryBoundaryTest {
    @Test
    void sessionEntrySubtypesOnlyContainConversationPathFacts() {
        JsonSubTypes subTypes = SessionEntry.class.getAnnotation(JsonSubTypes.class);

        Set<String> names = Arrays.stream(subTypes.value())
            .map(JsonSubTypes.Type::name)
            .collect(Collectors.toSet());

        assertThat(names).containsExactlyInAnyOrder(
            "message",
            "model_change",
            "thinking_change",
            "mode_change",
            "permission_mode_change",
            "permission_runtime_state_change",
            "permission_amendment",
            "shell_state_change",
            "compaction",
            "branch_summary",
            "custom",
            "custom_message",
            "label",
            "session_info"
        );
    }

    @Test
    void shellStateChangesStayInTheBranchButDoNotBecomeNavigableLeaves() {
        ShellStateChangeEntry entry = new ShellStateChangeEntry(
            "entry-shell",
            "entry-tool",
            ShellState.of(Path.of("/tmp/project/dir with spaces")),
            Instant.parse("2026-06-01T00:00:00Z")
        );

        assertThat(entry).isInstanceOf(SessionEntry.class);
        assertThat(SessionLeafSelector.advancesNavigableLeaf(entry)).isFalse();
    }

    @Test
    void fileChangeAndMemoryWriteAreNotSessionEntries() {
        assertThat(classExists("cn.lycode.contracts.session.FileChangeEntry")).isFalse();
        assertThat(classExists("cn.lycode.contracts.session.AgentLifecycleEntry")).isFalse();
        assertThat(classExists("cn.lycode.contracts.tui.FileChangeView")).isFalse();
        assertThat(SessionEntry.class.isAssignableFrom(MemoryWriteEntry.class)).isFalse();
    }

    private boolean classExists(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
