package cn.lypi.contracts.transport;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.tui.SessionRuntimeState;

public interface TransportAdapter {
    /*
    * @status : 未完成
    * @summary : 返回传输适配器名称。
    *@description : 名称用于启动装配和诊断输出。
    *
    *
                              */
    String name();

    /*
    * @status : 未完成
    * @summary : 挂载事件流与 session 状态。
    *@description : transport 只能消费事件和状态，不拥有核心业务事实源。
    *
    *
                              */
    void attach(EventBus events, SessionRuntimeState state);
}

