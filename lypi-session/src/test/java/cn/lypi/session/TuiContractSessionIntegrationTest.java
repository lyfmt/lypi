package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.session.CompactionEntry;
import cn.lypi.contracts.session.CompactionKind;
import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.ForkRequest;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.session.ModeChangeEntry;
import cn.lypi.contracts.session.ModelChangeEntry;
import cn.lypi.contracts.session.PermissionModeChangeEntry;
import cn.lypi.contracts.session.SessionContext;
import cn.lypi.contracts.session.SessionHandle;
import cn.lypi.contracts.session.SessionView;
import cn.lypi.contracts.session.ThinkingChangeEntry;
import cn.lypi.contracts.tui.FileUsageKind;
import cn.lypi.contracts.tui.GitDiffStatus;
import cn.lypi.contracts.tui.SessionFileView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TuiContractSessionIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-06-07T11:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void replayFilesDiffBranchAndSlashChangesStayDerivedFromLightweightSessionTree() throws Exception {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry("root", null, message("msg_root", MessageRole.USER, MessageKind.TEXT, "start"), NOW));
        manager.append(new ModelChangeEntry(
            "model_change",
            "root",
            new ModelSelection("openai", "gpt-5.4", ThinkingLevel.HIGH),
            "/model gpt-5.4",
            NOW
        ));
        manager.append(new ThinkingChangeEntry("thinking_change", "model_change", ThinkingLevel.HIGH, "/thinking high", NOW));
        manager.append(new ModeChangeEntry("mode_change", "thinking_change", AgentMode.PLAN, "/mode plan", NOW));
        manager.append(new PermissionModeChangeEntry(
            "permission_mode_change",
            "mode_change",
            PermissionMode.PLAN,
            "/permission-mode plan",
            NOW
        ));
        manager.append(new MessageEntry(
            "tool_call",
            "permission_mode_change",
            assistantToolCall("msg_tool_call", tool("tool_write", "write", true, "src/App.java", 0)),
            NOW
        ));
        manager.append(new MessageEntry(
            "tool_result",
            "tool_call",
            toolResult("msg_tool_result", "tool_write", "wrote src/App.java", false),
            NOW
        ));
        manager.append(new CompactionEntry(
            "compact",
            "tool_result",
            "summary before tool result",
            "tool_result",
            8000,
            1000,
            CompactionKind.SESSION,
            NOW
        ));
        manager.append(new MessageEntry(
            "after_compact",
            "compact",
            message("msg_after", MessageRole.ASSISTANT, MessageKind.TEXT, "windowed output available through result ref"),
            NOW
        ));
        SessionHandle handle = manager.append(new CustomMessageEntry("fork_notice", "after_compact", "Switched to leaf after_compact", NOW));
        manager.append(new MessageEntry(
            "sibling",
            "permission_mode_change",
            assistantToolCall("msg_sibling", tool("tool_read", "read", true, "sibling-only.md", 0)),
            NOW
        ));

        manager.switchLeaf("after_compact");
        SessionView view = manager.currentView();
        SessionContext context = manager.context(view.leafId());
        List<SessionFileView> files = manager.files(view.leafId());

        assertThat(view).isEqualTo(new SessionView("ses_main", "after_compact"));
        assertThat(context.branchEntryIds()).containsExactly(
            "root",
            "model_change",
            "thinking_change",
            "mode_change",
            "permission_mode_change",
            "tool_call",
            "tool_result",
            "compact",
            "after_compact"
        );
        assertThat(context.model().modelId()).isEqualTo("gpt-5.4");
        assertThat(context.thinkingLevel()).isEqualTo(ThinkingLevel.HIGH);
        assertThat(context.mode()).isEqualTo(AgentMode.PLAN);
        assertThat(context.permissionMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(context.messages())
            .extracting(AgentMessage::id)
            .containsExactly("summary-compact", "msg_tool_result", "msg_after");
        assertThat(context.messages()).extracting(AgentMessage::id).doesNotContain("msg_sibling");
        assertThat(((ToolResultContentBlock) context.messages().get(1).content().getFirst()).metadata())
            .containsEntry("resultRefId", "out_ref_write")
            .containsEntry("resultUri", "tool-output://ses_main/tool_write");
        assertThat(manager.transcript(view.leafId())).isEqualTo(context.messages());
        assertThat(files)
            .singleElement()
            .satisfies(file -> {
                assertThat(file.path()).isEqualTo(Path.of("src/App.java"));
                assertThat(file.operations()).containsExactly(FileUsageKind.WRITE);
                assertThat(file.metadata()).containsEntry("toolUseId", "tool_write");
            });
        assertThat(entryTypes(handle.sessionFile())).contains(
            "model_change",
            "thinking_change",
            "mode_change",
            "permission_mode_change",
            "message",
            "compaction",
            "custom_message"
        ).doesNotContain(
            "command",
            "permission_decision",
            "tool_output",
            "tool_use_audit",
            "file_change"
        );

        SessionHandle fork = manager.fork(new ForkRequest("ses_main", "after_compact", tempDir.resolve("forked"), "test fork"));
        assertThat(fork.leafId()).isNotEqualTo("sibling");
        assertThat(fork.byId()).containsKeys("root", "after_compact").doesNotContainKey("sibling");

        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@example.com");
        runGit(tempDir, "config", "user.name", "Test User");
        Files.writeString(tempDir.resolve("src-App.java"), "tracked\n");
        runGit(tempDir, "add", "src-App.java");
        runGit(tempDir, "commit", "-m", "initial");
        Files.writeString(tempDir.resolve("src-App.java"), "modified\n");

        assertThat(new GitWorkingTreeDiffQuery(tempDir).diff())
            .filteredOn(file -> file.path().equals(Path.of("src-App.java")))
            .singleElement()
            .satisfies(file -> {
                assertThat(file.path()).isEqualTo(Path.of("src-App.java"));
                assertThat(file.status()).isEqualTo(GitDiffStatus.MODIFIED);
            });
    }

    private static AgentMessage message(String id, MessageRole role, MessageKind kind, String text) {
        return new AgentMessage(id, role, kind, List.of(new TextContentBlock(text)), NOW, Optional.empty(), Optional.empty());
    }

    private static AgentMessage assistantToolCall(String id, ContentBlock toolCall) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            List.of(toolCall),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static AgentMessage toolResult(String id, String toolUseId, String text, boolean error) {
        return new AgentMessage(
            id,
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            List.of(new ToolResultContentBlock(
                toolUseId,
                text,
                error,
                Map.of("resultRefId", "out_ref_write", "resultUri", "tool-output://ses_main/tool_write")
            )),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static ToolCallContentBlock tool(String toolUseId, String toolName, boolean complete, String path, int index) {
        return new ToolCallContentBlock(
            toolUseId,
            toolName,
            "",
            Map.of(
                "complete",
                complete,
                "input",
                Map.of("path", path),
                "blockIndex",
                index
            )
        );
    }

    private static void runGit(Path cwd, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(cwd.toFile());
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError(new String(process.getErrorStream().readAllBytes()));
        }
    }

    private static List<String> command(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }

    private static Set<String> entryTypes(Path sessionFile) throws Exception {
        Set<String> types = new LinkedHashSet<>();
        for (String line : Files.readAllLines(sessionFile)) {
            int marker = line.indexOf("\"type\":\"");
            if (marker >= 0) {
                int start = marker + "\"type\":\"".length();
                int end = line.indexOf('"', start);
                types.add(line.substring(start, end));
            }
        }
        return types;
    }
}
