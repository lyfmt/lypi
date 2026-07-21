package cn.lypi.transport.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
import cn.lypi.contracts.subagent.SubagentToolPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HeadlessSubagentJsonCodecTest {
    @Test
    void canonicalInputAndOutputRoundTripKeepAllIdentities() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentInput input = input();
        HeadlessSubagentOutput output = new HeadlessSubagentOutput(
            "inspect-session", "agent_1", "ses_child", "run_1", SubagentRunStatus.SUCCEEDED,
            "done", Optional.of("entry_final"), Optional.empty()
        );
        ByteArrayOutputStream inputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();

        codec.writeInput(input, inputBytes);
        codec.writeOutput(output, outputBytes);

        assertThat(codec.readInput(new ByteArrayInputStream(inputBytes.toByteArray()))).isEqualTo(input);
        assertThat(codec.readOutput(new ByteArrayInputStream(outputBytes.toByteArray()))).isEqualTo(output);
        assertThat(inputBytes.toString(StandardCharsets.UTF_8))
            .contains("\"taskName\":\"inspect-session\"")
            .contains("\"runId\":\"run_1\"")
            .doesNotContain("runMode")
            .doesNotContain("permissionMode")
            .doesNotContain("allowedTools");
    }

    @Test
    void rejectsRemovedCompatibilityFields() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "taskName":"inspect-session",
              "agentId":"agent_1",
              "childSessionId":"ses_child",
              "runId":"run_1",
              "parentSessionId":"ses_parent",
              "parentSpawnEntryId":"entry_spawn",
              "message":"inspect",
              "sessionCwd":"/tmp/project",
              "cwd":"/tmp/project",
              "toolPolicy":{"requestedTools":[],"effectiveTools":[]},
              "permissionRuntimeState":{
                "approvalPolicy":{"mode":"ON_REQUEST"},
                "activePermissionProfile":{"id":":workspace"},
                "legacyBehavior":{"defaultBashRequiresEscalation":true,"allowExplicitEscalationWithoutPrompt":false,"hardSafetyEnabled":true},
                "legacyPermissionMode":"AUTO"
              },
              "timeoutSeconds":30,
              "runMode":"CONTINUE"
            }
            """;

        assertThatThrownBy(() -> codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid headless subagent input JSON");
    }

    @Test
    void rejectsTrailingNonJsonTokens() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        codec.writeInput(input(), bytes);
        String json = bytes.toString(StandardCharsets.UTF_8) + "\nStarted LyPiApplication";

        assertThatThrownBy(() -> codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid headless subagent input JSON");
    }

    private HeadlessSubagentInput input() {
        return new HeadlessSubagentInput(
            "inspect-session",
            "agent_1",
            "ses_child",
            "run_1",
            "ses_parent",
            "entry_spawn",
            "inspect",
            Path.of("/tmp/project"),
            Path.of("/tmp/project"),
            new SubagentToolPolicy(List.of(), List.of("read", "grep", "glob")),
            PermissionRuntimeState.forMode(PermissionMode.AUTO),
            30
        );
    }
}
