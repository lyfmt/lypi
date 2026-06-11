package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 查询当前 cwd 下可恢复的 session 列表。
 */
public final class SessionResumeQuery {
    private final JsonlSessionStore store;

    public SessionResumeQuery(Path cwd) {
        this.store = new JsonlSessionStore(cwd);
    }

    /**
     * 返回 Pi session selector 风格的 session 信息。
     */
    public List<SessionResumeInfo> sessions() {
        return store.headers().stream()
            .map(this::info)
            .sorted(Comparator.comparing(SessionResumeInfo::modified).reversed())
            .toList();
    }

    private SessionResumeInfo info(SessionHeader header) {
        SessionFile file = store.read(header.id());
        List<SessionEntry> entries = file.entries();
        String leafId = entries.isEmpty() ? null : entries.getLast().id();
        List<String> messageTexts = entries.stream()
            .map(this::displayText)
            .filter(text -> !text.isBlank())
            .toList();
        Instant modified = entries.stream()
            .map(SessionEntry::timestamp)
            .max(Comparator.naturalOrder())
            .orElse(header.timestamp());
        return new SessionResumeInfo(
            store.sessionFile(header.id()),
            header.id(),
            header.cwd(),
            header.parentSessionId().map(store::sessionFile),
            leafId,
            header.timestamp(),
            modified,
            messageTexts.size(),
            messageTexts.isEmpty() ? "(no messages)" : messageTexts.getFirst(),
            messageTexts.stream().collect(Collectors.joining(" "))
        );
    }

    private String displayText(SessionEntry entry) {
        return switch (entry) {
            case MessageEntry messageEntry -> messageText(messageEntry.message());
            case CustomMessageEntry customMessage -> customMessage.content();
            case BranchSummaryEntry branchSummary -> branchSummary.summary();
            default -> "";
        };
    }

    private String messageText(AgentMessage message) {
        if (message == null || message.content() == null) {
            return "";
        }
        return message.content().stream()
            .map(this::contentText)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining(" "));
    }

    private String contentText(ContentBlock block) {
        if (block instanceof TextContentBlock text) {
            return text.text() == null ? "" : text.text();
        }
        return "";
    }
}
