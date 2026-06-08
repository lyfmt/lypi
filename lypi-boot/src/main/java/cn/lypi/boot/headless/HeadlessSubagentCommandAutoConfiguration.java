package cn.lypi.boot.headless;

import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.SessionManagerFactoryPort;
import cn.lypi.transport.headless.HeadlessSubagentJsonCodec;
import cn.lypi.transport.headless.HeadlessSubagentRunner;
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
    @ConditionalOnBean(HeadlessSubagentRunner.class)
    public HeadlessSubagentCommand headlessSubagentCommand(HeadlessSubagentRunner runner) {
        return new HeadlessSubagentCommand(runner);
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
