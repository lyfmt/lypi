package cn.lypi.boot;

import cn.lypi.boot.headless.HeadlessSubagentCommand;
import cn.lypi.contracts.runtime.AgentCommunicationPort;
import cn.lypi.contracts.runtime.AgentCenterPort;
import cn.lypi.contracts.runtime.AgentCoreFactoryPort;
import cn.lypi.contracts.runtime.ResourceRuntimePort;
import cn.lypi.contracts.runtime.SecurityRuntimePort;
import cn.lypi.contracts.runtime.ToolRuntimePort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LyPiApplicationContextTest {
    @Test
    void defaultApplicationContextWiresOnlySimplifiedSubagentRuntimeSurface() {
        new ApplicationContextRunner()
            .withUserConfiguration(LyPiApplication.class)
            .run(context -> {
                assertThat(context).hasSingleBean(SecurityRuntimePort.class);
                assertThat(context).hasSingleBean(ResourceRuntimePort.class);
                assertThat(context).hasSingleBean(ToolRuntimePort.class);
                assertThat(context).hasSingleBean(AgentCoreFactoryPort.class);
                assertThat(context).hasSingleBean(AgentCenterPort.class);
                assertThat(context).hasSingleBean(AgentCommunicationPort.class);
                assertThat(context).hasSingleBean(HeadlessSubagentCommand.class);
                assertThat(context).doesNotHaveBean("mailboxSlashCommandHandler");

                ToolRuntimePort toolRuntime = context.getBean(ToolRuntimePort.class);
                assertThat(toolRuntime.resolve("spawn_agent")).isPresent();
                assertThat(toolRuntime.resolve("wait_agent")).isPresent();
                for (String removed : List.of(
                    "continue_agent",
                    "read_agent_result",
                    "read_mailbox",
                    "accept_mailbox_message",
                    "stash_mailbox_message",
                    "discard_mailbox_message",
                    "interrupt_agent",
                    "list_agents"
                )) {
                    assertThat(toolRuntime.resolve(removed)).as(removed).isEmpty();
                }
            });
    }
}
