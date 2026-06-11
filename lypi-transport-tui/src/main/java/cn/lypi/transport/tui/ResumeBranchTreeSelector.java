package cn.lypi.transport.tui;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.tui.SessionTreeNodeView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class ResumeBranchTreeSelector {
    private final List<FlatNode> flatNodes;
    private final Map<String, FlatNode> byId = new LinkedHashMap<>();
    private final String currentLeafId;
    private final int maxVisible;
    private final java.util.Set<String> activePath = new java.util.LinkedHashSet<>();
    private FilterMode filterMode = FilterMode.DEFAULT;
    private List<FlatNode> visibleNodes = List.of();
    private int selectedIndex;
    private String lastSelectedId;

    ResumeBranchTreeSelector(List<SessionTreeNodeView> roots, String currentLeafId, int maxVisible) {
        this.currentLeafId = currentLeafId;
        this.maxVisible = Math.max(1, maxVisible);
        this.flatNodes = flatten(roots == null ? List.of() : roots);
        for (FlatNode node : flatNodes) {
            byId.put(node.entry().id(), node);
        }
        buildActivePath();
        applyFilter();
        selectedIndex = findNearestVisibleIndex(currentLeafId);
        lastSelectedId = selectedEntry().map(SessionEntry::id).orElse(null);
    }

    Optional<SessionEntry> selectedEntry() {
        if (visibleNodes.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(visibleNodes.get(selectedIndex).entry());
    }

    void moveUp() {
        if (!visibleNodes.isEmpty()) {
            selectedIndex = Math.max(0, selectedIndex - 1);
            rememberSelection();
        }
    }

    void moveDown() {
        if (!visibleNodes.isEmpty()) {
            selectedIndex = Math.min(visibleNodes.size() - 1, selectedIndex + 1);
            rememberSelection();
        }
    }

    void toggleUserOnly() {
        filterMode = filterMode == FilterMode.USER_ONLY ? FilterMode.DEFAULT : FilterMode.USER_ONLY;
        applyFilter();
    }

    List<String> render(int width) {
        int safeWidth = Math.max(1, width);
        List<String> lines = new ArrayList<>();
        if (visibleNodes.isEmpty()) {
            lines.add("  No entries found");
            lines.add("  (0/0)" + filterLabel());
            return lines;
        }
        int start = Math.max(0, Math.min(selectedIndex - maxVisible / 2, visibleNodes.size() - maxVisible));
        int end = Math.min(start + maxVisible, visibleNodes.size());
        for (int index = start; index < end; index++) {
            lines.add(renderLine(visibleNodes.get(index), index == selectedIndex, safeWidth));
        }
        lines.add(AnsiWidth.truncate("  (" + (selectedIndex + 1) + "/" + visibleNodes.size() + ")" + filterLabel(), safeWidth));
        return lines;
    }

    private String renderLine(FlatNode node, boolean selected, int width) {
        String cursor = selected ? "› " : "  ";
        String prefix = treePrefix(node);
        String active = activePath.contains(node.entry().id()) ? "• " : "";
        String content = displayText(node.entry());
        return AnsiWidth.truncate(cursor + prefix + active + content, width);
    }

    private String treePrefix(FlatNode node) {
        if (node.depth() == 0) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (int index = 0; index < node.depth() - 1; index++) {
            prefix.append("   ");
        }
        prefix.append(node.last() ? "└─ " : "├─ ");
        return prefix.toString();
    }

    private String displayText(SessionEntry entry) {
        return switch (entry) {
            case MessageEntry messageEntry -> messageDisplay(messageEntry.message());
            case CustomMessageEntry custom -> "[" + custom.id() + "]: " + normalize(custom.content());
            case BranchSummaryEntry summary -> "[branch summary]: " + normalize(summary.summary());
            case CompactionEntry ignored -> "[compaction]";
            case ModelChangeEntry model -> "[model: " + model.model().modelId() + "]";
            case ThinkingChangeEntry thinking -> "[thinking: " + thinking.thinkingLevel() + "]";
            default -> "[" + entry.getClass().getSimpleName() + "]";
        };
    }

    private String messageDisplay(AgentMessage message) {
        if (message == null) {
            return "(no content)";
        }
        String text = message.content() == null ? "" : message.content().stream()
            .filter(TextContentBlock.class::isInstance)
            .map(TextContentBlock.class::cast)
            .map(TextContentBlock::text)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("");
        String role = message.role() == null ? "message" : message.role().name().toLowerCase();
        return role + ": " + (text.isBlank() ? "(no content)" : normalize(text));
    }

    private void applyFilter() {
        if (!visibleNodes.isEmpty()) {
            rememberSelection();
        }
        visibleNodes = flatNodes.stream()
            .filter(this::passesFilter)
            .toList();
        selectedIndex = findNearestVisibleIndex(lastSelectedId);
        rememberSelection();
    }

    private boolean passesFilter(FlatNode node) {
        SessionEntry entry = node.entry();
        if (filterMode == FilterMode.USER_ONLY) {
            return entry instanceof MessageEntry messageEntry
                && messageEntry.message() != null
                && messageEntry.message().role() == cn.lypi.contracts.context.MessageRole.USER;
        }
        return !(entry instanceof ModelChangeEntry)
            && !(entry instanceof ThinkingChangeEntry)
            && !(entry instanceof cn.lypi.contracts.session.ModeChangeEntry)
            && !(entry instanceof cn.lypi.contracts.session.PermissionModeChangeEntry)
            && !(entry instanceof cn.lypi.contracts.session.SessionInfoEntry)
            && !(entry instanceof cn.lypi.contracts.session.LabelEntry)
            && !(entry instanceof cn.lypi.contracts.session.CustomEntry);
    }

    private int findNearestVisibleIndex(String entryId) {
        if (visibleNodes.isEmpty()) {
            return 0;
        }
        Map<String, Integer> visibleById = new LinkedHashMap<>();
        for (int index = 0; index < visibleNodes.size(); index++) {
            visibleById.put(visibleNodes.get(index).entry().id(), index);
        }
        String current = entryId;
        while (current != null) {
            Integer index = visibleById.get(current);
            if (index != null) {
                return index;
            }
            FlatNode node = byId.get(current);
            current = node == null ? null : node.entry().parentId();
        }
        return Math.max(0, visibleNodes.size() - 1);
    }

    private void rememberSelection() {
        lastSelectedId = selectedEntry().map(SessionEntry::id).orElse(lastSelectedId);
    }

    private void buildActivePath() {
        String current = currentLeafId;
        while (current != null) {
            activePath.add(current);
            FlatNode node = byId.get(current);
            current = node == null ? null : node.entry().parentId();
        }
    }

    private List<FlatNode> flatten(List<SessionTreeNodeView> roots) {
        List<FlatNode> result = new ArrayList<>();
        for (int index = 0; index < roots.size(); index++) {
            flattenNode(roots.get(index), 0, index == roots.size() - 1, result);
        }
        return result;
    }

    private void flattenNode(SessionTreeNodeView node, int depth, boolean last, List<FlatNode> result) {
        result.add(new FlatNode(node.entry(), depth, last));
        for (int index = 0; index < node.children().size(); index++) {
            flattenNode(node.children().get(index), depth + 1, index == node.children().size() - 1, result);
        }
    }

    private String filterLabel() {
        return filterMode == FilterMode.USER_ONLY ? " [user]" : "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("[\\n\\t]", " ").trim();
    }

    private enum FilterMode {
        DEFAULT,
        USER_ONLY
    }

    private record FlatNode(SessionEntry entry, int depth, boolean last) {
    }
}
