package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentWaitRequest;
import cn.lypi.contracts.subagent.SubagentWaitResult;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubagentWaitResultFactoryTest {
    @Test
    void createsCompletedWaitResultFromOutput() {
        HeadlessSubagentOutput output = new HeadlessSubagentOutput(
            "ses_child",
            SubagentRunStatus.SUCCEEDED,
            "summary",
            Optional.of("entry_final"),
            Optional.empty()
        );

        SubagentWaitResult result = SubagentWaitResultFactory.fromOutput("agent_1", "run_1", output);

        assertThat(result.agentId()).isEqualTo("agent_1");
        assertThat(result.childSessionId()).isEqualTo("ses_child");
        assertThat(result.runId()).isEqualTo("run_1");
        assertThat(result.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(result.summary()).contains("summary");
        assertThat(result.finalEntryId()).contains("entry_final");
        assertThat(result.errorMessage()).isEmpty();
    }

    @Test
    void omitsBlankSummary() {
        HeadlessSubagentOutput output = new HeadlessSubagentOutput(
            "ses_child",
            SubagentRunStatus.FAILED,
            " ",
            Optional.empty(),
            Optional.of("failed")
        );

        SubagentWaitResult result = SubagentWaitResultFactory.fromOutput("agent_1", "run_1", output);

        assertThat(result.summary()).isEmpty();
        assertThat(result.errorMessage()).contains("failed");
    }

    @Test
    void createsFailedWaitResultFromRequest() {
        SubagentWaitResult result = SubagentWaitResultFactory.failed(
            new SubagentWaitRequest(Optional.of("agent_1"), Optional.of("ses_child"), Optional.of("run_1"), 1, true),
            "not found"
        );

        assertThat(result.agentId()).isEqualTo("agent_1");
        assertThat(result.childSessionId()).isEqualTo("ses_child");
        assertThat(result.runId()).isEqualTo("run_1");
        assertThat(result.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(result.errorMessage()).contains("not found");
    }
}
