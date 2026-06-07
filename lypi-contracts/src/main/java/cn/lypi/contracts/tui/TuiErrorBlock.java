package cn.lypi.contracts.tui;

public record TuiErrorBlock(
    String blockId,
    String message
) implements TuiBlock {
    public TuiErrorBlock {
        message = message == null ? "" : message;
    }

    @Override
    public TuiBlockKind kind() {
        return TuiBlockKind.ERROR;
    }
}
