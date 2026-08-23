package cn.lycode.boot;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.boot.ai.LyCodeAiAutoConfiguration;
import cn.lycode.boot.runtime.LyCodeRuntimeAutoConfiguration;
import cn.lycode.boot.tool.LyCodeToolAutoConfiguration;
import cn.lycode.contracts.bootstrap.BootstrapContext;
import cn.lycode.contracts.bootstrap.BootstrapRequest;
import cn.lycode.contracts.context.ContextBudget;
import cn.lycode.contracts.context.ContextSnapshot;
import cn.lycode.contracts.model.ModelSelection;
import cn.lycode.contracts.model.ThinkingLevel;
import cn.lycode.contracts.prompt.SystemPrompt;
import cn.lycode.contracts.runtime.SecurityRuntimePort;
import cn.lycode.contracts.runtime.SessionManagerPort;
import cn.lycode.contracts.runtime.ToolRuntimeInvocation;
import cn.lycode.contracts.runtime.ToolRuntimePort;
import cn.lycode.contracts.security.AgentMode;
import cn.lycode.contracts.security.ApprovalMode;
import cn.lycode.contracts.security.PermissionBehavior;
import cn.lycode.contracts.security.PermissionDecision;
import cn.lycode.contracts.security.PermissionDecisionReason;
import cn.lycode.contracts.security.PermissionMode;
import cn.lycode.contracts.security.PermissionRuntimeState;
import cn.lycode.contracts.session.PermissionRuntimeStateChangeEntry;
import cn.lycode.contracts.session.SessionContext;
import cn.lycode.contracts.session.SessionHandle;
import cn.lycode.contracts.tool.ToolResult;
import cn.lycode.contracts.tool.ToolUseContext;
import cn.lycode.contracts.tool.ToolUseRequest;
import cn.lycode.session.SessionManagerImpl;
import cn.lycode.tool.PermissionGateResult;
import cn.lycode.tool.PermissionPromptPort;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PermissionRuntimeEndToEndTest {
    @TempDir
    Path tempDir;

    @Test
    void bootPermissionConfigSurvivesSessionReplayAndBootstrapPrompt() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyCodeAiAutoConfiguration.class,
                LyCodeToolAutoConfiguration.class,
                LyCodeRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                "lycode.runtime.cwd=" + tempDir,
                "lycode.ai.default-provider=openai",
                "lycode.ai.default-model=gpt-5-mini",
                "lycode.permissions.default-permissions=:read-only",
                "lycode.permissions.approval-policy.mode=granular",
                "lycode.permissions.approval-policy.granular.sandbox-approval=on_request",
                "lycode.permissions.approval-policy.granular.rules=never",
                "lycode.permissions.approval-policy.granular.skill-approval=on_request",
                "lycode.permissions.approval-policy.granular.request-permissions=on_request",
                "lycode.permissions.approval-policy.granular.mcp-elicitations=never"
            );

        runner.run(context -> {
            SessionManagerPort sessionManager = context.getBean(SessionManagerPort.class);
            SessionHandle handle = sessionManager.openOrCreate("ses_permission_e2e");
            PermissionRuntimeState configured = sessionManager.context(handle.leafId()).permissionRuntimeState();

            assertThat(configured.approvalPolicy().mode()).isEqualTo(ApprovalMode.GRANULAR);
            assertThat(configured.activePermissionProfile().id()).isEqualTo(":read-only");

            PermissionRuntimeState elevated = PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS);
            SessionHandle changed = sessionManager.append(new PermissionRuntimeStateChangeEntry(
                "entry-permission-runtime",
                handle.leafId(),
                elevated,
                Instant.parse("2026-06-18T00:00:00Z")
            ));
            SessionManagerPort reopened = new SessionManagerImpl(tempDir);
            reopened.openOrCreate("ses_permission_e2e");
            SessionContext replayed = reopened.context(changed.leafId());

            assertThat(replayed.permissionRuntimeState()).isEqualTo(elevated);
            assertThat(replayed.permissionMode()).isEqualTo(elevated.legacyPermissionMode());
        });

        runner.run(context -> {
            BootstrapContext bootstrap = context.getBean(BootstrapService.class).bootstrap(new BootstrapRequest(
                tempDir,
                List.of(),
                Optional.of("ses_permission_e2e"),
                Optional.empty()
            ));

            assertThat(bootstrap.systemPrompt().content())
                .contains("## Permissions")
                .contains("approval policy metadata: NEVER")
                .contains("active sandbox profile: :danger-full-access")
                .contains("request_permissions")
                .contains("sandboxPermissions=requireEscalated")
                .contains("sandboxPermissions=withAdditionalPermissions");
        });
    }

    @Test
    void bootToolRuntimeCarriesStrictAutoReviewFromRequestPermissionsToLaterCommand() {
        AtomicInteger prompts = new AtomicInteger();
        AtomicInteger strictBashPrompts = new AtomicInteger();

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                LyCodeToolAutoConfiguration.class,
                LyCodeRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                "lycode.runtime.cwd=" + tempDir,
                "lycode.runtime.transport=tui"
            )
            .withBean(SecurityRuntimePort.class, () -> PermissionRuntimeEndToEndTest::strictAutoReviewSecurity)
            .withBean(PermissionPromptPort.class, () -> handle -> {
                prompts.incrementAndGet();
                if ("bash".equals(handle.request().toolName())
                    && handle.decision().metadata().containsKey("strictAutoReview")) {
                    strictBashPrompts.incrementAndGet();
                }
                return PermissionGateResult.allow();
            })
            .run(context -> {
                ToolRuntimePort runtime = context.getBean(ToolRuntimePort.class);
                ToolRuntimeInvocation invocation = new ToolRuntimeInvocation("ses_strict_auto_review", "turn_1", "entry_1");

                ToolResult<?> requestResult = runtime.execute(
                    List.of(new ToolUseRequest(
                        "toolu_perm",
                        "request_permissions",
                        Map.of(
                            "reason", "need workspace access before command",
                            "strictAutoReview", true,
                            "permissions", Map.of("fileSystem", Map.of(
                                "kind", "RESTRICTED",
                                "entries", List.of(Map.of(
                                    "path", Map.of("kind", "EXACT_PATH", "value", tempDir.resolve("review.txt").toString()),
                                    "access", "WRITE"
                                ))
                            ))
                        ),
                        "msg_1"
                    )),
                    contextSnapshot(),
                    invocation
                ).getFirst();
                ToolResult<?> bashResult = runtime.execute(
                    List.of(new ToolUseRequest("toolu_bash", "bash", Map.of("command", "echo ok"), "msg_2")),
                    contextSnapshot(),
                    invocation
                ).getFirst();

                assertThat(requestResult.isError()).isFalse();
                assertThat(bashResult.isError()).isFalse();
                assertThat(prompts.get()).isEqualTo(2);
                assertThat(strictBashPrompts.get()).isEqualTo(1);
            });
    }

    private static PermissionDecision strictAutoReviewSecurity(ToolUseRequest request, ToolUseContext context) {
        if (Boolean.TRUE.equals(context.metadata().get("strictAutoReview"))) {
            return new PermissionDecision(
                PermissionBehavior.ASK,
                PermissionDecisionReason.SANDBOX_POLICY,
                "strictAutoReview 要求本轮后续命令先进入人工 review。",
                Optional.empty(),
                Map.of("strictAutoReview", true)
            );
        }
        return new PermissionDecision(
            PermissionBehavior.ALLOW,
            PermissionDecisionReason.MODE_DEFAULT,
            "allowed",
            Optional.empty(),
            Map.of()
        );
    }

    private static ContextSnapshot contextSnapshot() {
        return new ContextSnapshot(
            new SystemPrompt("system", List.of(), "hash"),
            List.of(),
            new ModelSelection("provider", "model", ThinkingLevel.MEDIUM),
            ThinkingLevel.MEDIUM,
            AgentMode.EXECUTE,
            PermissionRuntimeState.fromLegacy(PermissionMode.ASK),
            new ContextBudget(0, 0, 0, 0, 0, 0L, 0L, BigDecimal.ZERO)
        );
    }
}
