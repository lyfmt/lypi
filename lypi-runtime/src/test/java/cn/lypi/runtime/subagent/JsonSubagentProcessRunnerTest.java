package cn.lypi.runtime.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonSubagentProcessRunnerTest {
    @TempDir
    Path tempDir;

    @Test
    void returnsTimedOutOutputWhenProcessExceedsTimeout() throws Exception {
        JsonSubagentProcessRunner runner = new JsonSubagentProcessRunner(List.of("bash", "-c", "sleep 5"));

        HeadlessSubagentOutput output = runner.start(input(1)).completion().get(3, TimeUnit.SECONDS);

        assertThat(output.status()).isEqualTo(SubagentRunStatus.TIMED_OUT);
        assertThat(output.childSessionId()).isEqualTo("ses_child");
        assertThat(output.errorMessage()).hasValueSatisfying(message -> assertThat(message).contains("timed out"));
    }

    @Test
    void interruptCompletesPromptlyEvenWhenProcessIgnoresTerm() throws Exception {
        JsonSubagentProcessRunner runner = new JsonSubagentProcessRunner(List.of("bash", "-c", "trap '' TERM; sleep 30"));
        SubagentProcessHandle handle = runner.start(input(30));

        handle.interrupt();
        HeadlessSubagentOutput output = handle.completion().get(3, TimeUnit.SECONDS);

        assertThat(output.status()).isEqualTo(SubagentRunStatus.INTERRUPTED);
        assertThat(output.childSessionId()).isEqualTo("ses_child");
    }

    @Test
    void startsProcessInRequestedCwd() throws Exception {
        JsonSubagentProcessRunner runner = new JsonSubagentProcessRunner(List.of(
            "python3",
            "-c",
            "import json, os, sys; sys.stdin.read(); print(json.dumps({'childSessionId':'ses_child','status':'SUCCEEDED','summary':os.getcwd(),'finalEntryId':'msg_final'}))"
        ));

        HeadlessSubagentOutput output = runner.start(input(30)).completion().get(3, TimeUnit.SECONDS);

        assertThat(output.status()).isEqualTo(SubagentRunStatus.SUCCEEDED);
        assertThat(output.summary()).isEqualTo(tempDir.toAbsolutePath().normalize().toString());
    }

    @Test
    void rejectsStdoutWithTrailingNonJsonTokens() throws Exception {
        JsonSubagentProcessRunner runner = new JsonSubagentProcessRunner(List.of(
            "python3",
            "-c",
            "import json, sys; sys.stdin.read(); print(json.dumps({'childSessionId':'ses_child','status':'SUCCEEDED','summary':'done','finalEntryId':'entry_final'})); print('Started LyPiApplication')"
        ));

        HeadlessSubagentOutput output = runner.start(input(30)).completion().get(3, TimeUnit.SECONDS);

        assertThat(output.status()).isEqualTo(SubagentRunStatus.FAILED);
        assertThat(output.errorMessage()).hasValueSatisfying(message ->
            assertThat(message).contains("Failed to read subagent output"));
    }

    private HeadlessSubagentInput input(int timeoutSeconds) {
        return new HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "请审查代码",
            tempDir,
            List.of(),
            PermissionMode.DEFAULT_EXECUTE,
            timeoutSeconds
        );
    }
}
