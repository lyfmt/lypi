package cn.lypi.tool;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.ContextBudget;
import cn.lypi.contracts.context.ContextSnapshot;
import cn.lypi.contracts.context.MessageKind;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.TextContentBlock;
import cn.lypi.contracts.context.ThinkingContentBlock;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import cn.lypi.contracts.model.ModelSelection;
import cn.lypi.contracts.model.ThinkingLevel;
import cn.lypi.contracts.prompt.SystemPrompt;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionReviewContextBuilderTest {
    private final PermissionReviewContextBuilder builder = new PermissionReviewContextBuilder();

    @Test
    void buildsCodexShapedTranscriptAndExactApprovalRequest() {
        List<AgentMessage> messages = List.of(
            message("user-1", MessageRole.USER, MessageKind.TEXT, new TextContentBlock("First user request")),
            message(
                "assistant-1",
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                new ThinkingContentBlock("private reasoning must not appear"),
                new TextContentBlock("I will inspect the workspace", Map.of(
                    "providerConversationState",
                    Map.of("previousResponseId", "response-secret")
                )),
                new ToolCallContentBlock(
                    "tool-old",
                    "bash",
                    "",
                    Map.of("input", Map.of("command", "pwd"), "complete", true)
                )
            ),
            message(
                "tool-result-1",
                MessageRole.TOOL_RESULT,
                MessageKind.TOOL_RESULT,
                new ToolResultContentBlock("tool-old", "/workspace", false)
            ),
            message("system-local", MessageRole.SYSTEM_LOCAL, MessageKind.TEXT, new TextContentBlock("synthetic secret")),
            message("user-2", MessageRole.USER, MessageKind.TEXT, new TextContentBlock("Please update notes.txt")),
            message(
                "assistant-pending",
                MessageRole.ASSISTANT,
                MessageKind.TOOL_CALL,
                new ToolCallContentBlock(
                    "tool-pending",
                    "write-notes",
                    "",
                    Map.of("input", Map.of("historyDuplicate", "must-not-appear"), "complete", true)
                )
            ),
            message("summary", MessageRole.USER, MessageKind.SUMMARY, new TextContentBlock("Compacted synthetic summary"))
        );
        ContextSnapshot parent = context(messages);

        ContextSnapshot review = builder.build(
            request(Map.of("path", "notes.txt", "content", "done")),
            tool(),
            toolContext(),
            parent,
            decision()
        );

        assertEquals(List.of("permission-reviewer-policy"), review.systemPrompt().sourceNames());
        assertTrue(review.systemPrompt().content().contains("Only transcript entries explicitly labeled `user`"));
        assertFalse(review.systemPrompt().content().contains(parent.systemPrompt().content()));
        assertTrue(review.systemPrompt().contentHash().startsWith("sha256:"));
        assertEquals(parent.model(), review.model());
        assertEquals(parent.thinkingLevel(), review.thinkingLevel());
        assertEquals(parent.mode(), review.mode());
        assertEquals(parent.permissionRuntimeState(), review.permissionRuntimeState());
        assertEquals(parent.budget().effectiveContextWindow(), review.budget().effectiveContextWindow());
        assertFalse(parent.budget().estimatedContextTokens() == review.budget().estimatedContextTokens());

        List<ContentBlock> blocks = review.messages().getFirst().content();
        String prompt = promptText(review);
        assertOrdered(
            prompt,
            ">>> TRANSCRIPT START",
            "[1] user: First user request",
            "[2] assistant: I will inspect the workspace",
            "[3] tool bash call: {\"command\":\"pwd\"}",
            "[4] tool bash result: /workspace",
            "[5] user: Please update notes.txt",
            "[6] summary: Compacted synthetic summary",
            ">>> TRANSCRIPT END",
            ">>> APPROVAL REQUEST START",
            "Planned action JSON:",
            ">>> APPROVAL REQUEST END"
        );
        assertTrue(blocks.size() > 8);
        assertFalse(prompt.contains("private reasoning must not appear"));
        assertFalse(prompt.contains("synthetic secret"));
        assertFalse(prompt.contains("response-secret"));
        assertFalse(prompt.contains("must-not-appear"));
        assertTrue(prompt.contains("\"tool\" : \"write-notes\""));
        assertTrue(prompt.contains("\"cwd\" : \"/workspace\""));
        assertTrue(prompt.contains("\"renderedSummary\" : \"write-notes {"));
        assertTrue(prompt.contains("\"input\" : {"));
        assertTrue(prompt.contains("\"content\" : \"done\""));
        assertTrue(prompt.contains("\"permissionDecision\" : {"));
        assertTrue(prompt.contains("\"reason\" : \"PATH_SAFETY\""));
        assertTrue(prompt.contains("\"message\" : \"outside workspace\""));
        assertTrue(prompt.contains("\"risk\" : \"outside-root\""));
    }

    @Test
    void preservesUserAnchorsAndRecentToolEvidenceWithSeparateBudgets() {
        List<AgentMessage> messages = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            messages.add(message(
                "user-" + index,
                MessageRole.USER,
                MessageKind.TEXT,
                new TextContentBlock("user-marker-" + index + " " + "u".repeat(7_900))
            ));
        }
        messages.add(message(
            "assistant-tool",
            MessageRole.ASSISTANT,
            MessageKind.TOOL_CALL,
            new ToolCallContentBlock(
                "tool-recent",
                "bash",
                "",
                Map.of("input", Map.of("command", "recent-tool-marker " + "t".repeat(20_000)), "complete", true)
            )
        ));
        messages.add(message(
            "tool-result",
            MessageRole.TOOL_RESULT,
            MessageKind.TOOL_RESULT,
            new ToolResultContentBlock("tool-recent", "recent-result-marker", false)
        ));

        ContextSnapshot review = builder.build(request(Map.of()), tool(), toolContext(), context(messages), decision());
        String prompt = promptText(review);

        assertTrue(prompt.contains("[1] user: user-marker-0"));
        assertTrue(prompt.contains("[8] user: user-marker-7"));
        assertTrue(prompt.contains("[9] tool bash call: {\"command\":\"recent-tool-marker"));
        assertTrue(prompt.contains("[10] tool bash result: recent-result-marker"));
        assertTrue(prompt.contains("<truncated omitted_approx_tokens=\""));
        assertTrue(prompt.contains("Some conversation entries were omitted."));
        assertFalse(prompt.contains("[2] user: user-marker-1"));
    }

    @Test
    void capsRecentNonUserEntriesWithoutDroppingLatestEvidence() {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(message("user", MessageRole.USER, MessageKind.TEXT, new TextContentBlock("Do the requested work")));
        for (int index = 0; index < 45; index++) {
            messages.add(message(
                "assistant-" + index,
                MessageRole.ASSISTANT,
                MessageKind.TEXT,
                new TextContentBlock("assistant-marker-" + index)
            ));
        }

        ContextSnapshot review = builder.build(request(Map.of()), tool(), toolContext(), context(messages), decision());
        String prompt = promptText(review);

        assertTrue(prompt.contains("[1] user: Do the requested work"));
        assertFalse(prompt.contains("[2] assistant: assistant-marker-0"));
        assertTrue(prompt.contains("[46] assistant: assistant-marker-44"));
        assertTrue(prompt.contains("Some conversation entries were omitted."));
    }

    @Test
    void truncatesActionStringsWithoutSplittingSurrogatePairs() {
        String face = "\uD83D\uDE00";
        String longValue = "prefix-" + face + "-" + "x".repeat(70_000) + "-" + face + "-suffix";

        ContextSnapshot review = builder.build(
            request(Map.of("content", longValue)),
            tool(),
            toolContext(),
            context(List.of(message("user", MessageRole.USER, MessageKind.TEXT, new TextContentBlock("Write it")))),
            decision()
        );
        String prompt = promptText(review);

        assertTrue(prompt.contains("<truncated omitted_approx_tokens=\\\""));
        assertTrue(prompt.contains("prefix-" + face));
        assertTrue(prompt.contains(face + "-suffix"));
        assertNoUnpairedSurrogates(prompt);
    }

    private ContextSnapshot context(List<AgentMessage> messages) {
        return new ContextSnapshot(
            new SystemPrompt("main system prompt must remain isolated", List.of("main"), "main-hash"),
            List.copyOf(messages),
            new ModelSelection("provider", "model", ThinkingLevel.HIGH),
            ThinkingLevel.HIGH,
            AgentMode.EXECUTE,
            PermissionMode.AUTO,
            new ContextBudget(77, 128_000, 100_000, 8_192, 16_384, 11L, 22L, BigDecimal.ONE)
        );
    }

    private ToolUseRequest request(Map<String, Object> input) {
        return new ToolUseRequest("tool-pending", "write-notes", input, "assistant-pending");
    }

    private Tool<Map<String, Object>, String> tool() {
        return TestTools.permission("write-notes", PermissionBehavior.ASK);
    }

    private ToolUseContext toolContext() {
        return new ToolUseContext("session-1", "assistant-pending", Path.of("/workspace"), Map.of());
    }

    private PermissionDecision decision() {
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.PATH_SAFETY,
            "outside workspace",
            Optional.empty(),
            Map.of("risk", "outside-root")
        );
    }

    private AgentMessage message(
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
            Instant.EPOCH,
            Optional.empty(),
            Optional.empty()
        );
    }

    private String promptText(ContextSnapshot context) {
        return context.messages().getFirst().content().stream()
            .map(ContentBlock::text)
            .reduce("", String::concat);
    }

    private void assertOrdered(String text, String... fragments) {
        int previous = -1;
        for (String fragment : fragments) {
            int current = text.indexOf(fragment);
            assertTrue(current > previous, () -> "missing or out-of-order fragment: " + fragment);
            previous = current;
        }
    }

    private void assertNoUnpairedSurrogates(String text) {
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1)));
                index++;
            } else {
                assertFalse(Character.isLowSurrogate(current));
            }
        }
    }
}
