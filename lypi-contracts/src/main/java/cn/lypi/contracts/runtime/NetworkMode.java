package cn.lypi.contracts.runtime;

/**
 * 定义命令执行时的网络隔离模式。
 */
public enum NetworkMode {
    /**
     * 使用宿主网络命名空间。
     */
    HOST,

    /**
     * 禁用命令执行环境中的网络访问。
     */
    DISABLED
}
