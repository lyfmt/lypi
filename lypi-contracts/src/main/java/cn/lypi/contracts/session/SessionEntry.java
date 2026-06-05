package cn.lypi.contracts.session;

import cn.lypi.contracts.memory.MemoryWriteEntry;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = MessageEntry.class, name = "message"),
    @JsonSubTypes.Type(value = ModelChangeEntry.class, name = "model_change"),
    @JsonSubTypes.Type(value = ThinkingChangeEntry.class, name = "thinking_change"),
    @JsonSubTypes.Type(value = ModeChangeEntry.class, name = "mode_change"),
    @JsonSubTypes.Type(value = PermissionModeChangeEntry.class, name = "permission_mode_change"),
    @JsonSubTypes.Type(value = CompactionEntry.class, name = "compaction"),
    @JsonSubTypes.Type(value = FileChangeEntry.class, name = "file_change"),
    @JsonSubTypes.Type(value = MemoryWriteEntry.class, name = "memory_write"),
    @JsonSubTypes.Type(value = BranchSummaryEntry.class, name = "branch_summary"),
    @JsonSubTypes.Type(value = CustomMessageEntry.class, name = "custom_message"),
    @JsonSubTypes.Type(value = LabelEntry.class, name = "label"),
    @JsonSubTypes.Type(value = SessionInfoEntry.class, name = "session_info"),
    @JsonSubTypes.Type(value = PermissionDecisionEntry.class, name = "permission_decision"),
    @JsonSubTypes.Type(value = CommandEntry.class, name = "command"),
    @JsonSubTypes.Type(value = ToolUseAuditEntry.class, name = "tool_use_audit"),
    @JsonSubTypes.Type(value = ToolOutputEntry.class, name = "tool_output")
})
public interface SessionEntry {
    String id();

    String parentId();

    Instant timestamp();
}
