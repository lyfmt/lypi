package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.memory.MemoryWriteEntry;
import cn.lypi.contracts.session.SessionEntry;
import com.fasterxml.jackson.annotation.JsonSubTypes;
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
            "compaction",
            "branch_summary",
            "custom",
            "custom_message",
            "label",
            "session_info"
        );
    }

    @Test
    void fileChangeAndMemoryWriteAreNotSessionEntries() {
        assertThat(classExists("cn.lypi.contracts.session.FileChangeEntry")).isFalse();
        assertThat(classExists("cn.lypi.contracts.tui.FileChangeView")).isFalse();
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
