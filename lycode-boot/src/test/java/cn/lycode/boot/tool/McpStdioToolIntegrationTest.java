package cn.lycode.boot.tool;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.common.ProgressSink;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.context.ToolResultContentBlock;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.runtime.ExecutionRequest;
import cn.lycode.contracts.runtime.ExecutionResult;
import cn.lycode.contracts.runtime.Executor;
import cn.lycode.contracts.runtime.NetworkMode;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import cn.lycode.resource.DefaultResourceRuntime;
import cn.lycode.tool.PermissionGateResult;
import cn.lycode.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class McpStdioToolIntegrationTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesAndExecutesStdioMcpToolFromProjectConfig() throws Exception {
        writeMcpConfig(tempDir);

        new ApplicationContextRunner()
            .withUserConfiguration(LyCodeToolAutoConfiguration.class)
            .withPropertyValues("lycode.runtime.cwd=" + tempDir)
            .withBean(SecurityRuntimePort.class, () -> McpStdioToolIntegrationTest::allowAllSecurity)
            .withBean(Executor.class, McpStdioToolIntegrationTest::executor)
            .withBean(PermissionPromptPort.class, () -> handle -> PermissionGateResult.allow())
            .withBean(cn.lycode.contracts.runtime.ResourceRuntimePort.class, DefaultResourceRuntime::new)
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);

                assertThat(runtime.resolve("mcp__fake__echo")).isPresent();

                ToolResult<?> result = runtime.execute(
                    List.of(new ToolUseRequest("toolu_mcp", "mcp__fake__echo", Map.of("text", "hello"), "msg_1")),
                    context()
                ).getFirst();

                assertThat(result.isError()).isFalse();
                assertThat(((ToolResultContentBlock) result.newMessages().getFirst().content().getFirst()).text())
                    .contains("hello");
            });
    }

    private static void writeMcpConfig(Path cwd) throws Exception {
        Path configDir = cwd.resolve(".ly-code");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("mcp.json"), """
            {
              "mcpServers": {
                "fake": {
                  "transport": "STDIO",
                  "command": %s,
                  "startupTimeoutSeconds": 5,
                  "callTimeoutSeconds": 5
                }
              }
            }
            """.formatted(commandJson()));
    }

    private static String commandJson() {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home")).resolve("bin/java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(IntegrationFakeStdioMcpServer.class.getName());
        return command.stream()
            .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .toList()
            .toString();
    }

    private static PermissionDecision allowAllSecurity(ToolUseRequest request, ToolUseContext context) {
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.empty(),
            Map.of()
        );
    }

    private static Executor executor() {
        return new Executor() {
            @Override
            public String name() {
                return "test";
            }

            @Override
            public ExecutionResult execute(ExecutionRequest request, ProgressSink progress, cn.lycode.contracts.common.AbortSignal signal) {
                return new ExecutionResult(0, "", "", false, Optional.empty());
            }
        };
    }

    private static ContextSnapshot context() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionMode.ASK,
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }
}
