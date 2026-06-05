package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.memory.MemoryScope;
import cn.lypi.contracts.memory.MemoryWriteEntry;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.session.FileChangeEntry;
import cn.lypi.contracts.session.FileOperation;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.PermissionDecisionEntry;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ToolUseAuditEntry;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolOutputRef;
import cn.lypi.contracts.tool.ToolResultSummary;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefaultSessionReplayPortTest {
    @TempDir
    Path tempDir;

    @Test
    void replayBuildsViewFromRequestedLeafPathOnly() {
        SessionEngine engine = new SessionEngineImpl(tempDir);
        engine.openOrCreate("ses_main");
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        ToolResultSummary summary = new ToolResultSummary("bash succeeded", "hello", false, 0, false, 42L, Map.of());
        ToolOutputRef ref = new ToolOutputRef(
            "toolout_01",
            "ses_main",
            "toolu_right",
            "text/plain; charset=utf-8",
            "session_blob",
            ".lypi/tool-output/toolout_01.txt",
            "sha256:abc123",
            42L,
            Map.of()
        );

        engine.append(messageEntry("root_msg", null, "msg_root", "hello", t0));
        engine.append(messageEntry("left_msg", "root_msg", "msg_left", "left only", t0.plusSeconds(1)));
        engine.append(new FileChangeEntry(
            "left_file",
            "left_msg",
            Path.of("left.txt"),
            FileOperation.WRITE,
            null,
            "sha256:left",
            "+left",
            "toolu_left",
            "msg_left",
            t0.plusSeconds(2)
        ));
        engine.append(new ToolUseAuditEntry(
            "right_tool",
            "root_msg",
            "toolu_right",
            "msg_root",
            "turn_01",
            "bash",
            "Bash",
            "echo hello",
            ToolExecutionStatus.SUCCEEDED,
            0,
            summary,
            ref,
            t0.plusSeconds(3),
            t0.plusSeconds(4),
            1000L,
            Map.of(),
            t0.plusSeconds(4)
        ));
        engine.append(new PermissionDecisionEntry(
            "right_permission",
            "right_tool",
            "toolu_right",
            "bash",
            "echo hello",
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "允许",
                Optional.empty(),
                Map.of()
            ),
            t0.plusSeconds(5)
        ));
        engine.append(new MemoryWriteEntry(
            "right_memory",
            "right_permission",
            MemoryScope.PROJECT,
            Path.of("AGENTS.md"),
            "sha256:memory",
            "msg_root",
            t0.plusSeconds(6)
        ));

        SessionView view = new DefaultSessionReplayPort(engine).view("right_memory");

        assertThat(view.sessionId()).isEqualTo("ses_main");
        assertThat(view.leafId()).isEqualTo("right_memory");
        assertThat(view.transcript()).extracting(entry -> entry.message().id()).containsExactly("msg_root");
        assertThat(view.recentTools()).extracting(ToolUseAuditEntry::toolUseId).containsExactly("toolu_right");
        assertThat(view.recentPermissionDecisions()).extracting(PermissionDecisionEntry::toolUseId).containsExactly("toolu_right");
        assertThat(view.memoryWrites()).extracting(MemoryWriteEntry::contentHash).containsExactly("sha256:memory");
        assertThat(view.recentFileChanges()).isEmpty();
    }

    private MessageEntry messageEntry(String id, String parentId, String messageId, String text, Instant timestamp) {
        return new MessageEntry(
            id,
            parentId,
            new AgentMessage(
                messageId,
                MessageRole.USER,
                MessageKind.TEXT,
                List.of(new TextContentBlock(text)),
                timestamp,
                Optional.empty(),
                Optional.empty()
            ),
            timestamp
        );
    }
}
