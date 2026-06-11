package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.tui.SessionBranchTreeView;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class SessionBranchTreeQueryTest {
    private static final Instant NOW = Instant.parse("2026-06-10T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void treeReturnsSessionEntriesWithChildrenAndCurrentLeaf() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, message("msg_root", MessageRole.USER, "hello"), NOW));
        manager.append(new MessageEntry("left", "root", message("msg_left", MessageRole.ASSISTANT, "left"), NOW.plusSeconds(1)));
        manager.switchLeaf("root");
        manager.append(new MessageEntry("right", "root", message("msg_right", MessageRole.USER, "right"), NOW.plusSeconds(2)));
        manager.append(new ModelChangeEntry(
            "model",
            "right",
            new cn.lypi.contracts.model.ModelSelection(
                "openai",
                "gpt-5.4",
                cn.lypi.contracts.model.ThinkingLevel.MEDIUM
            ),
            "test",
            NOW.plusSeconds(3)
        ));

        SessionBranchTreeView view = new SessionBranchTreeQuery(tempDir).tree("ses_main");

        assertThat(view.sessionId()).isEqualTo("ses_main");
        assertThat(view.currentLeafId()).isEqualTo("model");
        assertThat(view.roots()).singleElement().satisfies(root -> {
            assertThat(root.entry().id()).isEqualTo("root");
            assertThat(root.children()).extracting(child -> child.entry().id()).containsExactly("left", "right");
            SessionTreeNodeView right = root.children().get(1);
            assertThat(right.children()).singleElement().satisfies(child -> assertThat(child.entry().id()).isEqualTo("model"));
        });
    }

    private AgentMessage message(String id, MessageRole role, String text) {
        return new AgentMessage(
            id,
            role,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }
}
