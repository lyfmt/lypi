package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionRuntimeStateChangeEntry;
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
            new PermissionRuntimeStateChangeEntry("permission-1", "model-1", PermissionMode.ASK, NOW),
            user("user-3", "asst-1", "sibling branch")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "permission-1", 10);

        assertEquals("user-2", selector.selectedEntry().orElseThrow().id());
        assertTrue(selector.render(100).stream().anyMatch(line -> line.contains("• user: active branch")));
        assertTrue(selector.render(100).stream().noneMatch(line -> line.contains("permission-1")));
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

    @Test
    void defaultFilterHidesProtocolMessagesWithoutReadableContent() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "这是个banner 不是这个字符"),
            assistant("asst-empty", "user-1", ""),
            toolResult("tool-empty", "asst-empty", ""),
            assistant("asst-text", "tool-empty", "明白了，你要的是 banner 样式")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "asst-text", 10);

        List<String> lines = selector.render(120);

        assertTrue(lines.stream().anyMatch(line -> line.contains("user: 这是个banner 不是这个字符")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("assistant: 明白了，你要的是 banner 样式")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("assistant: (no content)")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("tool_result: (no content)")));
    }

    @Test
    void defaultFilterRebuildsTreePrefixesAfterHiddenProtocolNodes() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "第一轮"),
            assistant("asst-empty", "user-1", ""),
            toolResult("tool-empty", "asst-empty", ""),
            assistant("asst-text", "tool-empty", "第一轮回复"),
            user("user-2", "asst-text", "当然是艺术字那种")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "user-2", 10);

        List<String> visible = selector.render(160).stream()
            .filter(line -> line.contains("user: ") || line.contains("assistant: "))
            .toList();

        assertEquals(3, visible.size());
        assertTrue(visible.get(0).contains("user: 第一轮"));
        assertTrue(visible.get(1).contains("assistant: 第一轮回复"));
        assertTrue(visible.get(2).contains("user: 当然是艺术字那种"));
        assertTrue(visible.get(1).indexOf("assistant:") < 12);
        assertTrue(visible.get(2).indexOf("user:") < 15);
    }

    @Test
    void defaultFilterHidesToolCallOnlyAssistant() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "hello"),
            assistantToolCall("asst-tool", "user-1"),
            user("user-2", "asst-tool", "follow up")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "user-2", 10);

        List<String> lines = selector.render(120);
        assertTrue(lines.stream().noneMatch(line -> line.contains("assistant: (no content)")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("user: hello")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("user: follow up")));
    }

    @Test
    void defaultFilterHidesAssistantWithTextAndToolCallEvenWhenCurrentLeaf() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "hello"),
            assistantTextAndToolCall("asst-tool", "user-1")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "asst-tool", 10);

        List<String> lines = selector.render(120);
        assertTrue(lines.stream().noneMatch(line -> line.contains("assistant: I will edit it")));
        assertEquals("user-1", selector.selectedEntry().orElseThrow().id());
    }

    @Test
    void defaultFilterReparentsVisibleDescendantsWhenIntermediateToolNodesAreHidden() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "root"),
            assistantToolCall("asst-tool", "user-1"),
            user("user-2", "asst-tool", "follow up"),
            assistant("asst-2", "user-2", "done")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "asst-2", 10);

        List<String> lines = selector.render(120);
        assertTrue(lines.stream().noneMatch(line -> line.contains("assistant: (no content)")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("  • user: root")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("  └─ • user: follow up")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("›    └─ • assistant: done")));
    }

    @Test
    void defaultFilterHidesToolResultButKeepsVisibleDescendants() {
        List<SessionEntry> entries = List.of(
            user("user-1", null, "root"),
            assistantToolCall("asst-tool", "user-1"),
            toolResult("tool-result", "asst-tool", "file contents"),
            assistant("asst-2", "tool-result", "done")
        );

        ResumeBranchTreeSelector selector = new ResumeBranchTreeSelector(tree(entries), "asst-2", 10);

        List<String> lines = selector.render(120);
        assertTrue(lines.stream().noneMatch(line -> line.contains("tool_result: (no content)")));
        assertTrue(lines.stream().noneMatch(line -> line.contains("assistant: (no content)")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("user: root")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("assistant: done")));
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

    private MessageEntry assistantToolCall(String id, String parentId) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                "msg_" + id,
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                List.of(new ToolCallContentBlock(
                    "tool_" + id,
                    "read",
                    "",
                    Map.of("complete", true, "input", Map.of("path", "main.c"))
                )),
                NOW,
                Optional.empty(),
                Optional.of("tool_calls")
            ),
            NOW
        );
    }

    private MessageEntry assistantTextAndToolCall(String id, String parentId) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                "msg_" + id,
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                List.of(
                    new ThinkingContentBlock("thinking"),
                    new TextContentBlock("I will edit it"),
                    new ToolCallContentBlock(
                        "tool_" + id,
                        "edit",
                        "",
                        Map.of("complete", true, "input", Map.of("path", "main.c"))
                    )
                ),
                NOW,
                Optional.empty(),
                Optional.of("tool_calls")
            ),
            NOW
        );
    }

    private MessageEntry toolResult(String id, String parentId, String text) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                "msg_" + id,
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                List.of(new ToolResultContentBlock("tool_" + parentId, text, false)),
                NOW,
                Optional.empty(),
                Optional.empty()
            ),
            NOW
        );
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
