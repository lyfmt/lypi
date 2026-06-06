package cn.lypi.agent;

import cn.lypi.agent.compact.DefaultCompactionSummarizer;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CompactionPlan;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.SessionEntry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static cn.lypi.agent.AgentCoreTestFixtures.NOW;
import static cn.lypi.agent.AgentCoreTestFixtures.assistantMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.minimalContext;
import static cn.lypi.agent.AgentCoreTestFixtures.toolResultMessage;
import static cn.lypi.agent.AgentCoreTestFixtures.userMessage;
import static org.assertj.core.api.Assertions.assertThat;

class DefaultCompactionSummarizerTest {
    @Test
    void writesStructuredSummaryWithImportantMessageDetails() {
        DefaultCompactionSummarizer summarizer = new DefaultCompactionSummarizer();
        List<SessionEntry> branchEntries = List.of(
            messageEntry("entry-user", "", userMessage("msg-user", "Need inspect pom.xml")),
            messageEntry("entry-assistant", "entry-user", assistantToolCallMessage()),
            messageEntry("entry-result", "entry-assistant", toolResultMessage("msg-result", "call-read", "pom.xml contains lypi-agent-core", false)),
            messageEntry("entry-kept", "entry-result", assistantMessage("msg-kept", "kept"))
        );
        CompactionPlan plan = new CompactionPlan(
            "entry-result",
            "entry-kept",
            List.of("entry-user", "entry-assistant", "entry-result"),
            CompactionKind.SESSION
        );

        String summary = summarizer.summarize(branchEntries, plan, minimalContext(List.of()));

        assertThat(summary).contains(
            "## Goal",
            "## Constraints & Preferences",
            "## Progress",
            "## Key Decisions",
            "## Next Steps",
            "## Critical Context"
        );
        assertThat(summary).contains("USER TEXT", "Need inspect pom.xml");
        assertThat(summary).contains("ASSISTANT TOOL_CALL", "tool=read_file", "pom.xml");
        assertThat(summary).contains("TOOL_RESULT TOOL_RESULT", "toolUseId=call-read", "lypi-agent-core");
        assertThat(summary).doesNotContain("msg-kept");
    }

    private static MessageEntry messageEntry(String id, String parentId, AgentMessage message) {
        return new MessageEntry(id, parentId, message, NOW);
    }

    private static AgentMessage assistantToolCallMessage() {
        return new AgentMessage(
            "msg-assistant",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(
                new TextContentBlock("I'll inspect it."),
                new ToolCallContentBlock("call-read", "read_file", "{\"path\":\"pom.xml\"}", Map.of())
            ),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }
}
