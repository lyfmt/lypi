package cn.lycode.boot.headless;

import cn.lycode.contracts.runtime.AgentCoreFactoryPort;
import cn.lycode.contracts.runtime.SessionManagerFactoryPort;
import cn.lycode.transport.headless.HeadlessSubagentJsonCodec;
import cn.lycode.transport.headless.HeadlessSubagentRunner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HeadlessSubagentCommandAutoConfiguration {
    /**
     * 创建 headless subagent JSON codec。
     */
    @Bean
    @ConditionalOnMissingBean
    public HeadlessSubagentJsonCodec headlessSubagentJsonCodec() {
        return new HeadlessSubagentJsonCodec();
    }

    /**
     * 创建 headless subagent runner。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({AgentCoreFactoryPort.class, SessionManagerFactoryPort.class})
    public HeadlessSubagentRunner headlessSubagentRunner(
        AgentCoreFactoryPort agentCoreFactory,
        SessionManagerFactoryPort sessionManagerFactory,
        HeadlessSubagentJsonCodec codec
    ) {
        return new HeadlessSubagentRunner(agentCoreFactory, sessionManagerFactory, codec);
    }

    /**
     * 创建 headless subagent command。
     */
    @Bean
    @ConditionalOnMissingBean
    public HeadlessSubagentCommand headlessSubagentCommand(
        ObjectProvider<AgentCoreFactoryPort> agentCoreFactory,
        ObjectProvider<SessionManagerFactoryPort> sessionManagerFactory,
        HeadlessSubagentJsonCodec codec
    ) {
        return new HeadlessSubagentCommand(() -> new HeadlessSubagentRunner(
            agentCoreFactory.getObject(),
            sessionManagerFactory.getObject(),
            codec
        ));
    }

    /**
     * 创建 headless subagent CLI runner。
     */
    @Bean
    @ConditionalOnMissingBean
    public HeadlessSubagentApplicationRunner headlessSubagentApplicationRunner(
        ObjectProvider<HeadlessSubagentCommand> command
    ) {
        return new HeadlessSubagentApplicationRunner(command::getIfAvailable);
    }
}
