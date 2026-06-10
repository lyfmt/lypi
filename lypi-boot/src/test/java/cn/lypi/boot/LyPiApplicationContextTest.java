package cn.lypi.boot;

import cn.lypi.boot.headless.HeadlessSubagentCommand;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import cn.lypi.transport.tui.MailboxSlashCommandHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiApplicationContextTest {
    @Test
    void defaultApplicationContextWiresSubagentHeadlessAndMailboxRuntime() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiApplication.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SecurityRuntimePort.class);
                assertThat(context).hasSingleBean(ResourceRuntimePort.class);
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                assertThat(context).hasSingleBean(AgentCoreFactoryPort.class);
                assertThat(context).hasSingleBean(AgentCenterPort.class);
                assertThat(context).hasSingleBean(HeadlessSubagentCommand.class);
                assertThat(context).hasSingleBean(MailboxSlashCommandHandler.class);

                ToolRuntimePort toolRuntime = context.getBean(ToolRuntimePort.class);
                assertThat(toolRuntime.resolve("spawn_agent")).isPresent();
            });
    }
}
