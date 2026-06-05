package cn.lypi.contracts.transport;

public interface TransportAdapter {
    /**
     * 返回传输适配器名称。
     *
     * 名称用于启动装配和诊断输出。
     */
    String name();

    /**
     * 挂载 transport 可消费的运行视图。
     *
     * NOTE: transport 只能消费事件和状态，不拥有核心业务事实源。
     */
    void attach(TransportRuntimeView runtimeView);
}
