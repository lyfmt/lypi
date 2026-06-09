package cn.lypi.transport.headless;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.subagent.HeadlessSubagentInput;
import cn.lypi.contracts.subagent.HeadlessSubagentOutput;
import cn.lypi.contracts.subagent.SubagentRunStatus;
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
              "permissionMode": "PLAN",
              "timeoutSeconds": 30
            }
            """;

        HeadlessSubagentInput input = codec.readInput(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertThat(input.childSessionId()).isEqualTo("ses_child");
        assertThat(input.parentSessionId()).isEqualTo("ses_parent");
        assertThat(input.parentSpawnEntryId()).isEqualTo("entry_spawn");
        assertThat(input.cwd()).isEqualTo(Path.of("/tmp/project"));
        assertThat(input.allowedTools()).containsExactly("read", "grep");
        assertThat(input.permissionMode()).isEqualTo(PermissionMode.PLAN);
        assertThat(input.timeoutSeconds()).isEqualTo(30);
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
}
