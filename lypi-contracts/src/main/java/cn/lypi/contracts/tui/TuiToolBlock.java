package cn.lypi.contracts.tui;

public record TuiToolBlock(
    String blockId,
    String messageId,
    String toolUseId,
    String toolName,
    TuiToolState state,
    String label,
    String details,
    boolean active
) implements TuiBlock {
    public TuiToolBlock {
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        state = state == null ? TuiToolState.PENDING : state;
        label = label == null ? toolName : label;
        details = details == null ? "" : details;
    }

    public TuiToolBlock(
        String blockId,
        String messageId,
        String toolUseId,
        String toolName,
        TuiToolState state,
        String label,
        boolean active
    ) {
        this(blockId, messageId, toolUseId, toolName, state, label, "", active);
    }

    @Override
    public TuiBlockKind kind() {
        return TuiBlockKind.TOOL;
    }
}
