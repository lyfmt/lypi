package cn.lypi.contracts.error;

public enum ErrorAction {
    RETRY,
    COMPACT_AND_RETRY,
    RETURN_TOOL_ERROR,
    ASK_USER,
    END_TURN
}

