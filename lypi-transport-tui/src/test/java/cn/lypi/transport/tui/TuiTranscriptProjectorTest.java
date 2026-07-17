package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.contracts.tui.TuiBlock;
import cn.lypi.contracts.tui.TuiMessageBlock;
import cn.lypi.contracts.tui.TuiToolBlock;
import cn.lypi.contracts.tui.TuiToolState;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TuiTranscriptProjectorTest {
    private static final Instant NOW = Instant.parse("2026-07-10T00:00:00Z");

    @ParameterizedTest
    @MethodSource("resultStates")
    void projectsCompletedResultState(boolean error, String status, TuiToolState expectedState) {
        Map<String, Object> metadata = status.isBlank() ? Map.of() : Map.of("status", status);
        String resultText = "visible result\n" + "x".repeat(500);

        TuiToolBlock tool = onlyTool(project(List.of(
            assistant("call-message", call("call-1", "bash", "run checks")),
            result("result-message", new ToolResultContentBlock("call-1", resultText, error, metadata))
        )));

        assertEquals(expectedState, tool.state());
        assertFalse(tool.active());
        assertTrue(tool.details().contains("visible result"));
        assertTrue(tool.details().codePointCount(0, tool.details().length()) <= 200);
    }

    private static Stream<Arguments> resultStates() {
        return Stream.of(
            Arguments.of(false, "", TuiToolState.DONE),
            Arguments.of(true, "", TuiToolState.FAILED),
            Arguments.of(true, "CANCELLED", TuiToolState.CANCELLED),
            Arguments.of(true, "TIMED_OUT", TuiToolState.FAILED)
        );
    }

    @Test
    void matchesOutOfOrderResultsWithoutMovingOriginalCalls() {
        List<TuiBlock> blocks = project(List.of(
            assistant(
                "calls",
                call("read-1", "read", "read AGENTS.md"),
                call("bash-1", "bash", "pwd")
            ),
            assistant("assistant-note", new TextContentBlock("working")),
            user("user-note", new TextContentBlock("continue")),
            result("bash-result", new ToolResultContentBlock("bash-1", "/workspace", false)),
            result("read-result", new ToolResultContentBlock("read-1", "project rules", false))
        ));

        List<TuiToolBlock> tools = tools(blocks);
        assertEquals(List.of("read-1", "bash-1"), tools.stream().map(TuiToolBlock::toolUseId).toList());
        assertEquals(List.of(TuiToolState.DONE, TuiToolState.DONE), tools.stream().map(TuiToolBlock::state).toList());
        assertTrue(tools.get(0).details().contains("project rules"));
        assertTrue(tools.get(1).details().contains("/workspace"));
        assertEquals("assistant", ((TuiMessageBlock) blocks.get(2)).role());
        assertEquals("user", ((TuiMessageBlock) blocks.get(3)).role());
    }

    @Test
    void keepsUnfinishedCallPendingAndProjectsUnmatchedResult() {
        List<TuiBlock> blocks = project(List.of(
            assistant("pending-call", call("pending-1", "read", "read README.md")),
            result(
                "orphan-result",
                new ToolResultContentBlock(
                    "orphan-1",
                    "orphan output",
                    false,
                    Map.of("toolName", "write")
                )
            )
        ));

        List<TuiToolBlock> tools = tools(blocks);
        assertEquals(2, tools.size());
        assertEquals(TuiToolState.PENDING, tools.get(0).state());
        assertFalse(tools.get(0).active());
        assertEquals("orphan-1", tools.get(1).toolUseId());
        assertEquals("write", tools.get(1).toolName());
        assertEquals(TuiToolState.DONE, tools.get(1).state());
        assertTrue(tools.get(1).details().contains("orphan output"));
        assertEquals(0, blocks.stream()
            .filter(TuiMessageBlock.class::isInstance)
            .map(TuiMessageBlock.class::cast)
            .filter(block -> "tool".equals(block.role()))
            .count());
    }

    @Test
    void usesStableFallbackIdsAndAppliesLastDuplicateResult() {
        List<TuiBlock> firstProjection = project(edgeCaseTranscript());
        List<TuiBlock> secondProjection = project(edgeCaseTranscript());

        List<TuiToolBlock> firstTools = tools(firstProjection);
        List<TuiToolBlock> secondTools = tools(secondProjection);
        assertEquals(3, firstTools.size());
        assertEquals(
            firstTools.stream().map(TuiToolBlock::blockId).toList(),
            secondTools.stream().map(TuiToolBlock::blockId).toList()
        );
        assertNotEquals(firstTools.get(0).blockId(), firstTools.get(1).blockId());
        assertNotEquals(firstTools.get(0).toolUseId(), firstTools.get(1).toolUseId());
        assertEquals("tool:blank-calls:tool_call:0", firstTools.get(0).blockId());
        assertEquals("tool:blank-calls:tool_call:1", firstTools.get(1).blockId());

        TuiToolBlock duplicate = firstTools.get(2);
        assertEquals("duplicate-1", duplicate.toolUseId());
        assertEquals(TuiToolState.FAILED, duplicate.state());
        assertTrue(duplicate.details().contains("second result"));
        assertFalse(duplicate.details().contains("first result"));
    }

    private static List<AgentMessage> edgeCaseTranscript() {
        return List.of(
            assistant(
                "blank-calls",
                call("", "read", "first blank"),
                call("", "bash", "second blank"),
                call("duplicate-1", "read", "first duplicate"),
                call("duplicate-1", "bash", "second duplicate")
            ),
            result("first-result", new ToolResultContentBlock("duplicate-1", "first result", false)),
            result("second-result", new ToolResultContentBlock("duplicate-1", "second result", true))
        );
    }

    private static ToolCallContentBlock call(String toolUseId, String toolName, String label) {
        return new ToolCallContentBlock(
            toolUseId,
            toolName,
            "",
            Map.of("inputSummary", label)
        );
    }

    private static AgentMessage assistant(String id, ContentBlock... blocks) {
        MessageKind kind = Stream.of(blocks).allMatch(ToolCallContentBlock.class::isInstance)
            ? MessageKind.TOOL_CALL
            : MessageKind.TEXT;
        return message(id, MessageRole.ASSISTANT, kind, blocks);
    }

    private static AgentMessage user(String id, ContentBlock... blocks) {
        return message(id, MessageRole.USER, MessageKind.TEXT, blocks);
    }

    private static AgentMessage result(String id, ContentBlock... blocks) {
        return message(id, MessageRole.TOOL_RESULT, MessageKind.TOOL_RESULT, blocks);
    }

    private static AgentMessage message(
        String id,
        MessageRole role,
        MessageKind kind,
        ContentBlock... blocks
    ) {
        return new AgentMessage(
            id,
            role,
            kind,
            List.of(blocks),
            NOW,
            Optional.empty(),
            Optional.empty()
        );
    }

    private static List<TuiBlock> project(List<AgentMessage> transcript) {
        SessionRuntimeState base = TestRuntimeStates.basic("session-1");
        SessionRuntimeState runtimeState = new SessionRuntimeState(
            base.sessionId(),
            base.cwd(),
            base.currentBranchLeafId(),
            base.model(),
            base.thinkingLevel(),
            base.agentMode(),
            base.permissionRuntimeState(),
            base.budget(),
            transcript,
            false,
            false,
            false,
            false
        );
        return TuiEventReducer.fromRuntimeState(runtimeState).view().blocks();
    }

    private static TuiToolBlock onlyTool(List<TuiBlock> blocks) {
        List<TuiToolBlock> tools = tools(blocks);
        assertEquals(1, tools.size());
        return tools.getFirst();
    }

    private static List<TuiToolBlock> tools(List<TuiBlock> blocks) {
        return blocks.stream()
            .filter(TuiToolBlock.class::isInstance)
            .map(TuiToolBlock.class::cast)
            .toList();
    }
}
