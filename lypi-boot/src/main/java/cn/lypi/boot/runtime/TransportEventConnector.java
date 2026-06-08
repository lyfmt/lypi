package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import java.util.List;
import java.util.Objects;

/**
 * 将 transport 连接到统一事件总线。
 *
 * NOTE: connector 不创建 session 状态；调用方必须传入启动或运行时确定的状态快照。
 */
public final class TransportEventConnector {
    private final EventBus eventBus;
    private final List<TransportAdapter> transports;

    public TransportEventConnector(EventBus eventBus, List<TransportAdapter> transports) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus must not be null");
        this.transports = transports == null ? List.of() : List.copyOf(transports);
    }

    /**
     * 将所有 transport 挂载到同一个事件总线和 session 状态。
     */
    public void attachAll(SessionRuntimeState state) {
        SessionRuntimeState safeState = Objects.requireNonNull(state, "state must not be null");
        for (TransportAdapter transport : transports) {
            transport.attach(eventBus, safeState);
        }
    }
}
