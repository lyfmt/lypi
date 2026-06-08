package cn.lypi.boot.runtime;

import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.ChildSessionPort;
import cn.lypi.contracts.runtime.MailboxPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerPort;
import cn.lypi.contracts.event.EventBus;
import cn.lypi.contracts.transport.TransportAdapter;
import cn.lypi.contracts.tui.SessionRuntimeState;
import cn.lypi.runtime.event.InMemoryEventBus;
import cn.lypi.runtime.subagent.DefaultAgentCenter;
import cn.lypi.runtime.subagent.DefaultMailboxService;
import cn.lypi.runtime.subagent.JsonSubagentProcessRunner;
import cn.lypi.runtime.subagent.JsonlMailboxStore;
import cn.lypi.runtime.subagent.MailboxDeliveryGuard;
import cn.lypi.runtime.subagent.MailboxDeliveryService;
import cn.lypi.runtime.subagent.SubagentProcessRunner;
import cn.lypi.session.ChildSessionService;
import cn.lypi.session.DefaultSessionManagerFactory;
import cn.lypi.session.SessionManagerImpl;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LyPiRuntimeAutoConfiguration {
    private static final Path DEFAULT_CWD = Path.of(".").toAbsolutePath().normalize();

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

    /**
     * 创建默认时钟。
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 创建默认 session manager factory。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerFactoryPort.class)
    public SessionManagerFactoryPort sessionManagerFactory() {
        return new DefaultSessionManagerFactory();
    }

    /**
     * 创建默认父 session manager。
     *
     * NOTE: 仅构造 manager，不主动创建 session 文件；真实 session id 由上层启动流程打开。
     */
    @Bean
    @ConditionalOnMissingBean(SessionManagerPort.class)
    public SessionManagerPort sessionManager() {
        return new SessionManagerImpl(DEFAULT_CWD);
    }

    /**
     * 创建 child session 服务。
     */
    @Bean
    @ConditionalOnMissingBean(ChildSessionPort.class)
    public ChildSessionPort childSessionPort() {
        return new ChildSessionService();
    }

    /**
     * 创建 mailbox JSONL store。
     */
    @Bean
    @ConditionalOnMissingBean
    public JsonlMailboxStore jsonlMailboxStore() {
        return new JsonlMailboxStore(DEFAULT_CWD);
    }

    /**
     * 创建默认 mailbox 服务。
     */
    @Bean
    @ConditionalOnMissingBean(MailboxPort.class)
    public DefaultMailboxService mailboxPort(JsonlMailboxStore store, SessionManagerPort sessionManager, Clock clock) {
        return new DefaultMailboxService(store, sessionManager, clock);
    }

    /**
     * 创建默认 mailbox 投递守卫。
     *
     * NOTE: 默认保守不自动投递，由 TUI/headless 空闲检测后替换。
     */
    @Bean
    @ConditionalOnMissingBean
    public MailboxDeliveryGuard mailboxDeliveryGuard() {
        return message -> false;
    }

    /**
     * 创建 mailbox 投递服务。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultMailboxService.class)
    public MailboxDeliveryService mailboxDeliveryService(DefaultMailboxService mailbox, MailboxDeliveryGuard guard) {
        return new MailboxDeliveryService(mailbox, guard);
    }

    /**
     * 创建 JSON subagent 子进程 runner。
     */
    @Bean
    @ConditionalOnMissingBean
    public SubagentProcessRunner subagentProcessRunner() {
        return new JsonSubagentProcessRunner(List.of());
    }

    /**
     * 创建默认 agent center。
     */
    @Bean
    @ConditionalOnMissingBean(AgentCenterPort.class)
    @ConditionalOnBean({DefaultMailboxService.class, MailboxDeliveryService.class})
    public AgentCenterPort agentCenter(
        ChildSessionPort childSessions,
        SessionManagerPort parentSession,
        SubagentProcessRunner processRunner,
        DefaultMailboxService mailbox,
        MailboxDeliveryService deliveryService,
        Clock clock
    ) {
        return new DefaultAgentCenter(
            List.of(),
            childSessions,
            parentSession,
            processRunner,
            mailbox,
            deliveryService,
            clock
        );
    }
}
