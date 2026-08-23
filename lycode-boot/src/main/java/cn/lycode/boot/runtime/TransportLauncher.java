package cn.lycode.boot.runtime;

import cn.lycode.contracts.event.EventBus;
import cn.lycode.contracts.runtime.AgentCorePort;
import cn.lycode.contracts.tui.SessionRuntimeState;

interface TransportLauncher {
    /**
     * 返回启动器名称。
     */
    String name();

    /**
     * 启动 transport。
     */
    void launch(SessionRuntimeState state, AgentCorePort core, EventBus events);
}
