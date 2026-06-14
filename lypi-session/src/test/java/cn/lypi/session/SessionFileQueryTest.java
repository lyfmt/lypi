package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.session.MessageEntry;
import cn.lypi.contracts.tui.FileUsageKind;
import cn.lypi.contracts.tui.SessionFileView;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionFileQueryTest {
    private static final Instant FIRST = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant SECOND = Instant.parse("2026-06-01T00:01:00Z");
    private static final Instant THIRD = Instant.parse("2026-06-01T00:02:00Z");

    @TempDir
    Path tempDir;

    @Test
    void filesDeriveCompletedReadWriteAndEditToolCallsFromCurrentBranch() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry(
            "root",
            null,
            assistant("msg-root", FIRST, List.of(
                tool("tool-read", "read", true, "src/App.java", 0),
                tool("tool-incomplete", "write", false, "draft.md", 1),
                tool("tool-bash", "bash", true, "scripts/build.sh", 2),
                tool("tool-empty", "edit", true, "", 3)
            )),
            FIRST
        ));
        manager.append(new MessageEntry(
            "left",
            "root",
            assistant("msg-left", SECOND, List.of(
                tool("tool-write", "write", true, "src/App.java", 0),
                tool("tool-edit", "edit", true, "README.md", 1)
            )),
            SECOND
        ));
        manager.append(new MessageEntry(
            "right",
            "root",
            assistant("msg-right", THIRD, List.of(tool("tool-right", "read", true, "right-only.md", 0))),
            THIRD
        ));

        List<SessionFileView> files = manager.files("left");

        assertThat(files).extracting(SessionFileView::path).containsExactly(Path.of("README.md"), Path.of("src/App.java"));
        assertThat(files.get(0).operations()).containsExactly(FileUsageKind.EDIT);
        assertThat(files.get(0).lastResultAt()).isEqualTo(SECOND);
        assertThat(files.get(1).operations()).containsExactlyInAnyOrder(FileUsageKind.READ, FileUsageKind.WRITE);
        assertThat(files.get(1).lastResultAt()).isEqualTo(SECOND);
        assertThat(files.get(1).metadata())
            .containsEntry("toolUseId", "tool-write")
            .containsEntry("blockIndex", 0);
    }

    @Test
    void completedToolCallWithoutToolResultStillEntersFiles() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry(
            "root",
            null,
            assistant("msg-root", FIRST, List.of(tool("tool-read", "read", true, "README.md", 0))),
            FIRST
        ));

        List<SessionFileView> files = manager.files("root");

        assertThat(files).singleElement().satisfies(file -> {
            assertThat(file.path()).isEqualTo(Path.of("README.md"));
            assertThat(file.operations()).containsExactly(FileUsageKind.READ);
        });
    }

    @Test
    void filesSkipMalformedToolCallMetadata() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry(
            "root",
            null,
            assistant("msg-root", FIRST, List.of(
                new ToolCallContentBlock("tool-null", "read", "", null),
                new ToolCallContentBlock("tool-missing-input", "read", "", Map.of("complete", true)),
                new ToolCallContentBlock(
                    "tool-non-string-path",
                    "write",
                    "",
                    Map.of("complete", true, "input", Map.of("path", 1))
                ),
                tool("tool-read", "read", true, "README.md", 3)
            )),
            FIRST
        ));

        List<SessionFileView> files = manager.files("root");

        assertThat(files).singleElement().satisfies(file -> assertThat(file.path()).isEqualTo(Path.of("README.md")));
    }

    @Test
    void filesIgnoreToolCallBlocksInNonToolCallMessages() {
        SessionManager manager = new SessionManagerImpl(tempDir);
        manager.openOrCreate("ses_main");
        manager.append(new MessageEntry(
            "root",
            null,
            assistant("msg-root", FIRST, List.of(tool("tool-text", "read", true, "notes.md", 0)), MessageKind.TEXT),
            FIRST
        ));

        List<SessionFileView> files = manager.files("root");

        assertThat(files).isEmpty();
    }

    private static AgentMessage assistant(String id, Instant timestamp, List<ContentBlock> content) {
        return assistant(id, timestamp, content, MessageKind.TOOL_CALL);
    }

    private static AgentMessage assistant(String id, Instant timestamp, List<ContentBlock> content, MessageKind kind) {
        return new AgentMessage(
            id,
            MessageRole.ASSISTANT,
            kind,
            content,
            timestamp,
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
}
