package cn.lypi.transport.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalMode;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.HeadlessSubagentRunMode;
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
    void inputRoundTripKeepsHeadlessFields() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "cwd": "/tmp/project",
              "allowedTools": ["read", "grep"],
              "permissionMode": "DEFAULT_EXECUTE",
              "timeoutSeconds": 30
            }
            """;

        HeadlessSubagentInput input = codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(input.childSessionId()).isEqualTo("ses_child");
        assertThat(input.parentSessionId()).isEqualTo("ses_parent");
        assertThat(input.parentSpawnEntryId()).isEqualTo("entry_spawn");
        assertThat(input.cwd()).isEqualTo(Path.of("/tmp/project"));
        assertThat(input.allowedTools()).containsExactly("read", "grep");
        assertThat(input.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
        assertThat(input.timeoutSeconds()).isEqualTo(30);
    }

    @Test
    void inputReadsCanonicalPermissionRuntimeStateWhenLegacyPermissionModeIsAbsent() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "allowedTools": ["read", "grep"],
              "permissionRuntimeState": {
                "approvalPolicy": {
                  "mode": "UNLESS_TRUSTED"
                },
                "activePermissionProfile": {
                  "id": ":workspace-write"
                },
                "legacyBehavior": {
                  "defaultBashRequiresEscalation": false,
                  "allowExplicitEscalationWithoutPrompt": false,
                  "hardSafetyEnabled": false
                },
                "legacyPermissionMode": "DEFAULT_EXECUTE"
              },
              "timeoutSeconds": 30
            }
            """;

        HeadlessSubagentInput input = codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(input.permissionRuntimeState()).isEqualTo(customPermissionRuntimeState());
        assertThat(input.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void inputPrefersCanonicalPermissionRuntimeStateOverLegacyPermissionMode() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "sessionCwd": "/tmp/project/.ly-pi",
              "cwd": "/tmp/project",
              "allowedTools": ["read", "grep"],
              "permissionMode": "BYPASS",
              "permissionRuntimeState": {
                "approvalPolicy": {
                  "mode": "UNLESS_TRUSTED"
                },
                "activePermissionProfile": {
                  "id": ":workspace-write"
                },
                "legacyBehavior": {
                  "defaultBashRequiresEscalation": false,
                  "allowExplicitEscalationWithoutPrompt": false,
                  "hardSafetyEnabled": false
                },
                "legacyPermissionMode": "DEFAULT_EXECUTE"
              },
              "timeoutSeconds": 30
            }
            """;

        HeadlessSubagentInput input = codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(input.permissionRuntimeState()).isEqualTo(customPermissionRuntimeState());
        assertThat(input.permissionMode()).isEqualTo(PermissionMode.DEFAULT_EXECUTE);
    }

    @Test
    void inputWriteIncludesCanonicalPermissionRuntimeStateForNewProtocol() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentInput input = new HeadlessSubagentInput(
            "ses_child",
            "ses_parent",
            "entry_spawn",
            "请审查代码",
            Path.of("/tmp/project/.ly-pi"),
            Path.of("/tmp/project"),
            List.of("read"),
            new SubagentToolPolicy(List.of("read"), List.of("read", "grep")),
            customPermissionRuntimeState(),
            30,
            HeadlessSubagentRunMode.START,
            List.of()
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        codec.writeInput(input, out);

        String json = out.toString(StandardCharsets.UTF_8);
        assertThat(json).contains("\"permissionRuntimeState\"");
        assertThat(json).contains("\"approvalPolicy\"");
        assertThat(json).contains("\"permissionMode\":\"DEFAULT_EXECUTE\"");
    }

    @Test
    void outputRoundTripKeepsStructuredFailureFields() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        HeadlessSubagentOutput output = new HeadlessSubagentOutput(
            "ses_child",
            SubagentRunStatus.FAILED,
            "执行失败",
            Optional.empty(),
            Optional.of("invalid input")
        );
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        codec.writeOutput(output, out);
        HeadlessSubagentOutput restored = codec.readOutput(new ByteArrayInputStream(out.toByteArray()));

        assertThat(restored).isEqualTo(output);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"status\":\"FAILED\"");
    }

    @Test
    void readInputRejectsTrailingNonJsonTokens() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "childSessionId": "ses_child",
              "parentSessionId": "ses_parent",
              "parentSpawnEntryId": "entry_spawn",
              "prompt": "请审查代码",
              "cwd": "/tmp/project",
              "permissionMode": "DEFAULT_EXECUTE",
              "timeoutSeconds": 30
            }
            Started LyPiApplication
            """;

        assertThatThrownBy(() -> codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid headless subagent input JSON");
    }

    @Test
    void readOutputRejectsTrailingNonJsonTokens() {
        HeadlessSubagentJsonCodec codec = new HeadlessSubagentJsonCodec();
        String json = """
            {
              "childSessionId": "ses_child",
              "status": "SUCCEEDED",
              "summary": "完成",
              "finalEntryId": "entry_final"
            }
            Started LyPiApplication
            """;

        assertThatThrownBy(() -> codec.readOutput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid headless subagent output JSON");
    }

    private PermissionRuntimeState customPermissionRuntimeState() {
        return new PermissionRuntimeState(
            new ApprovalPolicy(ApprovalMode.UNLESS_TRUSTED),
            new ActivePermissionProfile(":workspace-write"),
            cn.lypi.contracts.security.PermissionProfiles.readOnly(),
            new LegacyPermissionBehavior(false, false, false),
            PermissionMode.DEFAULT_EXECUTE
        );
    }
}
