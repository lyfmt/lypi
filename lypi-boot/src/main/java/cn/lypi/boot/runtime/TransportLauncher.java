package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.runtime.AgentCorePort;
import cn.lypi.contracts.tui.SessionRuntimeState;

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
