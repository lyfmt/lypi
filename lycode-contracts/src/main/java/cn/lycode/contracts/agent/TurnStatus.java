package cn.lycode.contracts.agent;

public enum TurnStatus {
    RUNNING,
    WAITING_PERMISSION,
    RETRYING,
    ABORTED,
    FAILED,
    COMPLETED
}

