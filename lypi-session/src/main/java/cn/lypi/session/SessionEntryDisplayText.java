package cn.lypi.session;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.session.BranchSummaryEntry;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.stream.Collectors;

final class SessionEntryDisplayText {
    private SessionEntryDisplayText() {
    }

    static String text(SessionEntry entry) {
        return switch (entry) {
            case MessageEntry messageEntry -> messageText(messageEntry.message());
            case CustomMessageEntry customMessage -> blankIfNull(customMessage.content());
            case BranchSummaryEntry branchSummary -> blankIfNull(branchSummary.summary());
            default -> "";
        };
    }

    private static String blankIfNull(String text) {
        return text == null ? "" : text;
    }

    private static String messageText(AgentMessage message) {
        if (message == null || message.content() == null) {
            return "";
        }
        return message.content().stream()
            .map(SessionEntryDisplayText::contentText)
            .filter(text -> !text.isBlank())
            .collect(Collectors.joining(" "));
    }

    private static String contentText(ContentBlock block) {
        if (block instanceof TextContentBlock text) {
            return text.text() == null ? "" : text.text();
        }
        return "";
    }
}
