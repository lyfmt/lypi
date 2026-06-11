package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ResumeBranchTreeSelectorTest {
    private static final Instant NOW = Instant.parse("2026-06-10T00:00:00Z");

    @Test
    void focusesNearestVisibleAncestorWhenCurrentLeafIsMetadataEntry() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "hello"),
            assistant("asst-1", "user-1", "hi"),
            user("user-2", "asst-1", "active branch"),
            new ModelChangeEntry("model-1", "user-2", new ModelSelection("openai", "gpt-5.4", ThinkingLevel.MEDIUM), "test", NOW),
            user("user-3", "asst-1", "sibling branch")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "model-1", 10);

        assertEquals("user-2", selector.selectedEntry().orElseThrow().id());
        assertTrue(selector.render(100).stream().anyMatch(line -> line.contains("• user: active branch")));
    }

    @Test
    void userOnlyFilterWalksSelectionToNearestVisibleUserEntry() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "hello"),
            assistant("asst-1", "user-1", "hi"),
            user("user-2", "asst-1", "active branch"),
            assistant("asst-2", "user-2", "response"),
            user("user-3", "asst-1", "sibling branch")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "asst-2", 10);
        selector.toggleUserOnly();

        assertEquals("user-2", selector.selectedEntry().orElseThrow().id());
        assertTrue(selector.render(100).stream().anyMatch(line -> line.contains("[user]")));
    }

    @Test
    void supportsScrollingAndSelection() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "one"),
            user("user-2", "user-1", "two"),
            user("user-3", "user-2", "three"),
            user("user-4", "user-3", "four")
        );
        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "user-4", 2);

        selector.moveUp();

        assertEquals("user-3", selector.selectedEntry().orElseThrow().id());
        assertTrue(selector.render(80).stream().anyMatch(line -> line.contains("(3/4)")));
        assertTrue(selector.render(80).stream().noneMatch(line -> line.contains("user: one")));
    }

    private List<SessionTreeNodeView> tree(List<SessionEntry> entries) {
        Map<String, MutableNode> nodes = new LinkedHashMap<>();
        for (SessionEntry entry : entries) {
            nodes.put(entry.id(), new MutableNode(entry));
        }
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : nodes.values()) {
            if (node.entry().parentId() != null && nodes.containsKey(node.entry().parentId())) {
                nodes.get(node.entry().parentId()).children().add(node);
            } else {
                roots.add(node);
            }
        }
        return roots.stream().map(this::view).toList();
    }

    private SessionTreeNodeView view(MutableNode node) {
        return new SessionTreeNodeView(node.entry(), node.children().stream().map(this::view).toList());
    }

    private MessageEntry user(String id, String parentId, String text) {
        return new MessageEntry(id, parentId, message(id, MessageRole.USER, text), NOW);
    }

    private MessageEntry assistant(String id, String parentId, String text) {
        return new MessageEntry(id, parentId, message(id, MessageRole.ASSISTANT, text), NOW);
    }

    private AgentMessage message(String id, MessageRole role, String text) {
        return new AgentMessage(
            "msg_" + id,
            role,
            MessageKind.TEXT,
            List.of(new TextContentBlock(text)),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private record MutableNode(SessionEntry entry, List<MutableNode> children) {
        private MutableNode(SessionEntry entry) {
            this(entry, new ArrayList<>());
        }
    }
}
