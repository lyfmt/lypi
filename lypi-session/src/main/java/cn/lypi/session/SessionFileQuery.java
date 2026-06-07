package cn.lypi.session;

import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.tui.FileUsageKind;
import cn.lypi.contracts.tui.SessionFileView;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SessionFileQuery {
    List<SessionFileView> files(List<SessionEntry> branch) {
        Map<Path, MutableFileView> views = new LinkedHashMap<>();
        for (SessionEntry entry : branch) {
            if (!(entry instanceof MessageEntry messageEntry)) {
                continue;
            }
            if (
                messageEntry.message().role() != MessageRole.ASSISTANT ||
                messageEntry.message().kind() != MessageKind.TOOL_CALL
            ) {
                continue;
            }
            List<ContentBlock> blocks = messageEntry.message().content();
            for (int i = 0; i < blocks.size(); i++) {
                ContentBlock block = blocks.get(i);
                if (!(block instanceof ToolCallContentBlock toolCall)) {
                    continue;
                }
                FileUsageKind operation = operation(toolCall.toolName());
                Map<String, Object> toolMetadata = toolCall.metadata();
                if (operation == null || toolMetadata == null || !Boolean.TRUE.equals(toolMetadata.get("complete"))) {
                    continue;
                }
                String path = path(toolMetadata.get("input"));
                if (path == null || path.isBlank()) {
                    continue;
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("toolUseId", toolCall.toolUseId());
                metadata.put("blockIndex", i);
                views.computeIfAbsent(Path.of(path), ignored -> new MutableFileView())
                    .merge(operation, messageEntry.timestamp(), metadata);
            }
        }
        return views.entrySet().stream()
            .map(entry -> entry.getValue().toView(entry.getKey()))
            .sorted(
                Comparator.comparing(SessionFileView::lastResultAt).reversed()
                    .thenComparing(view -> view.path().toString())
            )
            .toList();
    }

    private FileUsageKind operation(String toolName) {
        return switch (toolName) {
            case "read" -> FileUsageKind.READ;
            case "write" -> FileUsageKind.WRITE;
            case "edit" -> FileUsageKind.EDIT;
            default -> null;
        };
    }

    private String path(Object input) {
        if (!(input instanceof Map<?, ?> map)) {
            return null;
        }
        Object path = map.get("path");
        return path instanceof String string ? string : null;
    }

    private static final class MutableFileView {
        private final EnumSet<FileUsageKind> operations = EnumSet.noneOf(FileUsageKind.class);
        private Instant lastResultAt = Instant.EPOCH;
        private Map<String, Object> metadata = Map.of();

        void merge(FileUsageKind operation, Instant timestamp, Map<String, Object> metadata) {
            operations.add(operation);
            if (!timestamp.isBefore(lastResultAt)) {
                lastResultAt = timestamp;
                this.metadata = Map.copyOf(metadata);
            }
        }

        SessionFileView toView(Path path) {
            return new SessionFileView(path, EnumSet.copyOf(operations), lastResultAt, metadata);
        }
    }
}
