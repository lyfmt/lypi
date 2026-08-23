package cn.lycode.contracts.transport;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.tui.SessionRuntimeState;

public interface TransportAdapter {
    /**
     * 返回传输适配器名称。
     *
     * 名称用于启动装配和诊断输出。
     */
    String name();

    /**
     * 挂载事件流与 session 状态。
     *
     * NOTE: transport 只能消费事件和状态，不拥有核心业务事实源。
     */
    void attach(EventBus events, SessionRuntimeState state);
}
