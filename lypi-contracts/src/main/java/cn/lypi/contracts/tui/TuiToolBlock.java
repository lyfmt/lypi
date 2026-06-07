package cn.lypi.contracts.tui;

public record TuiToolBlock(
    String blockId,
    String toolUseId,
    String toolName,
    TuiToolState state,
    String label,
    boolean active
) implements TuiBlock {
    public TuiToolBlock {
        toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
        state = state == null ? TuiToolState.PENDING : state;
        label = label == null ? toolName : label;
    }

    @Override
    public TuiBlockKind kind() {
        return TuiBlockKind.TOOL;
    }
}
