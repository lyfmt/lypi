package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.context.ContentBlockKind;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.event.CompactEndEvent;
import cn.lypi.contracts.event.CompactStartEvent;
import cn.lypi.contracts.event.MessageDeltaEvent;
import cn.lypi.contracts.event.MessageEndEvent;
import cn.lypi.contracts.event.PermissionDecisionEvent;
import cn.lypi.contracts.event.PermissionRequestEvent;
import cn.lypi.contracts.event.RetryEndEvent;
import cn.lypi.contracts.event.RetryStartEvent;
import cn.lypi.contracts.event.ToolEndEvent;
import cn.lypi.contracts.event.ToolProgressEvent;
import cn.lypi.contracts.event.ToolStartEvent;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.tool.ToolExecutionStatus;
import cn.lypi.contracts.tool.ToolResultSummary;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiThinkingBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import cn.lypi.contracts.tui.TuiViewModel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TuiReducerCoverageTest {
    private static final Instant NOW = Instant.parse("2026-06-08T10:00:00Z");

    @Test
    void retryAndCompactEventsMapToStableStatusBarState() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new RetryStartEvent("ses_1", 2, "provider.rate_limit", NOW));
        assertEquals("retrying attempt 2: provider.rate_limit", reducer.view().statusBar().mode());

        reducer.reduce(new RetryEndEvent("ses_1", 2, true, NOW.plusMillis(1)));
        assertEquals("ready", reducer.view().statusBar().mode());

        reducer.reduce(new CompactStartEvent("ses_1", "SESSION", NOW.plusMillis(2)));
        assertEquals("compacting SESSION", reducer.view().statusBar().mode());

        reducer.reduce(new CompactEndEvent("ses_1", "entry-compact-1", NOW.plusMillis(3)));
        assertEquals("ready", reducer.view().statusBar().mode());
    }

    @Test
    void permissionAndToolEventsMapToStableOverlayAndToolState() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new PermissionRequestEvent("ses_1", "toolu_permission", "Approve edit?", NOW));
        TuiViewModel requested = reducer.view();

        assertTrue(requested.permissionPrompt().isPresent());
        assertEquals("toolu_permission", requested.permissionPrompt().orElseThrow().toolUseId());
        assertEquals("allow_once", requested.permissionPrompt().orElseThrow().defaultOptionId());
        assertEquals("cancel", requested.permissionPrompt().orElseThrow().cancelOptionId());

        reducer.reduce(new PermissionDecisionEvent(
            "ses_1",
            "toolu_permission",
            new PermissionDecision(
                PermissionBehavior.ALLOW,
                PermissionDecisionReason.TOOL_SPECIFIC,
                "Approved",
                Optional.empty(),
                Map.of()
            ),
            NOW.plusMillis(1)
        ));

        assertTrue(reducer.view().permissionPrompt().isEmpty());

        reducer.reduce(new ToolStartEvent(
            "ses_1",
            "toolu_1",
            "msg_1",
            "turn_1",
            "bash",
            "Run tests",
            "mvn test",
            Map.of("command", "mvn test"),
            NOW.plusMillis(2),
            NOW.plusMillis(2)
        ));
        reducer.reduce(new ToolProgressEvent("ses_1", "toolu_1", ToolProgress.status("running", "tests"), NOW.plusMillis(3)));

        TuiToolBlock running = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiToolState.RUNNING, running.state());
        assertTrue(running.active());

        reducer.reduce(toolEnd("toolu_1", ToolExecutionStatus.SUCCEEDED));

        TuiToolBlock done = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiToolState.DONE, done.state());
        assertFalse(done.active());
        assertEquals("Run tests", done.label());
    }

    @Test
    void interleavedAssistantAndThinkingDeltasKeepFirstSeenBlockOrder() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(delta("msg_1", "text-block", ContentBlockKind.TEXT, "answer ", false));
        reducer.reduce(delta("msg_1", "thinking-block", ContentBlockKind.THINKING, "consider ", false));
        reducer.reduce(delta("msg_1", "text-block", ContentBlockKind.TEXT, "done", true));
        reducer.reduce(delta("msg_1", "thinking-block", ContentBlockKind.THINKING, "done", true));
        reducer.reduce(new MessageEndEvent("ses_1", "msg_1", NOW.plusMillis(1)));

        List<TuiBlock> blocks = reducer.view().blocks();

        TuiMessageBlock message = assertInstanceOf(TuiMessageBlock.class, blocks.get(0));
        TuiThinkingBlock thinking = assertInstanceOf(TuiThinkingBlock.class, blocks.get(1));
        assertEquals("text-block", message.blockId());
        assertEquals("answer done", message.content());
        assertFalse(message.streaming());
        assertEquals("thinking-block", thinking.blockId());
        assertEquals("consider done", thinking.content());
        assertFalse(thinking.streaming());
    }

    @Test
    void failedOrTimedOutToolEndDoesNotOverwriteLaterSuccessForSameToolUse() {
        TuiEventReducer reducer = new TuiEventReducer();

        reducer.reduce(new ToolStartEvent("ses_1", "toolu_retry", "bash", NOW));
        reducer.reduce(toolEnd("toolu_retry", ToolExecutionStatus.TIMED_OUT));
        reducer.reduce(toolEnd("toolu_retry", ToolExecutionStatus.SUCCEEDED));

        TuiToolBlock tool = assertInstanceOf(TuiToolBlock.class, reducer.view().blocks().getFirst());
        assertEquals(TuiToolState.DONE, tool.state());
        assertFalse(tool.active());
    }

    private MessageDeltaEvent delta(
        String messageId,
        String blockId,
        ContentBlockKind blockKind,
        String delta,
        boolean isFinal
    ) {
        return new MessageDeltaEvent(
            "ses_1",
            messageId,
            MessageRole.ASSISTANT,
            blockKind == ContentBlockKind.THINKING ? MessageKind.THINKING : MessageKind.TEXT,
            blockId,
            blockKind,
            delta,
            isFinal,
            Map.of(),
            NOW
        );
    }

    private ToolEndEvent toolEnd(String toolUseId, ToolExecutionStatus status) {
        return new ToolEndEvent(
            "ses_1",
            toolUseId,
            status,
            status == ToolExecutionStatus.SUCCEEDED ? 0 : 124,
            new ToolResultSummary(status.name(), "", status != ToolExecutionStatus.SUCCEEDED, null, status == ToolExecutionStatus.TIMED_OUT, 0L, Map.of()),
            null,
            NOW,
            NOW.plusMillis(10),
            10L,
            Map.of(),
            NOW.plusMillis(10)
        );
    }
}
