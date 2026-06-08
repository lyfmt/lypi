package cn.lypi.boot.runtime;

import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.event.InMemoryEventBus;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LyPiRuntimeAutoConfiguration {
    /**
     * 创建默认事件总线。
     */
    @Bean
    @ConditionalOnMissingBean(EventBus.class)
    public EventBus eventBus() {
        return new InMemoryEventBus();
    }

    /**
     * 创建 transport 事件连接器。
     */
    @Bean
    @ConditionalOnMissingBean
    public TransportEventConnector transportEventConnector(
        EventBus eventBus,
        ObjectProvider<TransportAdapter> transports,
        ObjectProvider<SessionRuntimeState> state
    ) {
        TransportEventConnector connector = new TransportEventConnector(eventBus, List.copyOf(transports.orderedStream().toList()));
        state.ifAvailable(connector::attachAll);
        return connector;
    }
}
