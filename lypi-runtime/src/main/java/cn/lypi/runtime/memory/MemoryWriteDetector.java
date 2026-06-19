package cn.lypi.runtime.memory;

import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.context.ContentBlock;
import cn.lypi.contracts.context.MessageRole;
import cn.lypi.contracts.context.ToolCallContentBlock;
import cn.lypi.contracts.context.ToolResultContentBlock;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 识别主 turn 是否已经直接写入 memory 目标。
 */
public final class MemoryWriteDetector {
    /**
     * 返回消息序列中是否存在对 memory 路径的写入类工具调用。
     */
    public boolean hasMemoryWrite(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        Set<String> successfulToolUseIds = successfulToolUseIds(messages);
        if (successfulToolUseIds.isEmpty()) {
            return false;
        }
        return messages.stream().anyMatch(message -> hasSuccessfulMemoryWrite(message, successfulToolUseIds));
    }

    private boolean hasSuccessfulMemoryWrite(AgentMessage message, Set<String> successfulToolUseIds) {
        if (message == null || message.role() != MessageRole.ASSISTANT || message.content() == null) {
            return false;
        }
        return message.content().stream().anyMatch(block -> isMemoryWriteToolCall(block, successfulToolUseIds));
    }

    private boolean isMemoryWriteToolCall(ContentBlock block, Set<String> successfulToolUseIds) {
        if (!(block instanceof ToolCallContentBlock toolCall)) {
            return false;
        }
        if (!successfulToolUseIds.contains(toolCall.toolUseId()) || !Boolean.TRUE.equals(toolCall.metadata().get("complete"))) {
            return false;
        }
        Object rawInput = toolCall.metadata() == null ? null : toolCall.metadata().get("input");
        if (!(rawInput instanceof Map<?, ?> input)) {
            return false;
        }
        if (isBashTool(toolCall.toolName())) {
            Object rawCommand = input.get("command");
            return rawCommand instanceof String command && containsMemoryPath(command);
        }
        if (!isWriteTool(toolCall.toolName())) {
            return false;
        }
        Object rawPath = input.get("path");
        return rawPath instanceof String path && isMemoryPath(path);
    }

    private Set<String> successfulToolUseIds(List<AgentMessage> messages) {
        Set<String> ids = new HashSet<>();
        for (AgentMessage message : messages) {
            if (message == null || message.content() == null) {
                continue;
            }
            for (ContentBlock block : message.content()) {
                if (block instanceof ToolResultContentBlock result && !result.error()) {
                    ids.add(result.toolUseId());
                }
            }
        }
        return ids;
    }

    private boolean isWriteTool(String toolName) {
        if (toolName == null) {
            return false;
        }
        String normalized = toolName.toLowerCase(Locale.ROOT);
        return normalized.equals("edit")
            || normalized.equals("write")
            || normalized.equals("multi_edit")
            || normalized.equals("multiedit");
    }

    private boolean isBashTool(String toolName) {
        return toolName != null && toolName.toLowerCase(Locale.ROOT).equals("bash");
    }

    private boolean containsMemoryPath(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String normalized = command.replace('\\', '/');
        return normalized.contains("MEMORY.md")
            || normalized.contains(".ly-pi/memory.md")
            || normalized.contains(".ly-pi/memory/")
            || normalized.contains(".ly-pi/skills/");
    }

    private boolean isMemoryPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return false;
        }
        String normalized = rawPath.replace('\\', '/');
        if (normalized.equals("MEMORY.md") || normalized.endsWith("/MEMORY.md")) {
            return true;
        }
        Path path = Path.of(rawPath).normalize();
        String lexical = path.toString().replace('\\', '/');
        return lexical.equals(".ly-pi/memory.md")
            || lexical.startsWith(".ly-pi/memory/")
            || lexical.startsWith(".ly-pi/skills/")
            || lexical.endsWith("/.ly-pi/memory.md")
            || lexical.contains("/.ly-pi/memory/")
            || lexical.contains("/.ly-pi/skills/")
            || lexical.endsWith("/.ly-pi/memory.md");
    }
}
