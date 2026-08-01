package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.AgentMode;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.ActivePermissionProfile;
import cn.lypi.contracts.security.ApprovalPolicy;
import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import cn.lypi.contracts.security.LegacyPermissionBehavior;
import cn.lypi.contracts.security.ManagedPermissionProfile;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionMode;
import cn.lypi.contracts.security.PermissionProfiles;
import cn.lypi.contracts.security.PermissionRuntimeState;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import cn.lypi.contracts.tool.ToolUseContext;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionDecisionPipelineTest {
    @Test
    void hardSafetyRunsBeforeExplicitAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "read_file", "*", "allow reads")
        ));

        PermissionDecision decision = pipeline.decide(
            request("read_file", Map.of("path", ".git/config")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
    }

    @Test
    void planModeDeniesSandboxEscalationBeforeModeDefault() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "id",
                "sandboxPermissions", "requireEscalated",
                "justification", "needs host namespace"
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesSandboxEscalationBeforeHardSafety() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "id",
                "cwd", ".git",
                "sandboxPermissions", "requireEscalated",
                "justification", "needs host namespace"
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesRequestPermissionsTool() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("request_permissions", Map.of("reason", "need write access")),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void planModeDeniesAdditionalPermissionsSandboxOverride() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of(
                "command", "touch cache/out",
                "sandboxPermissions", "withAdditionalPermissions",
                "additionalPermissions", Map.of()
            )),
            context(PermissionMode.BYPASS, Map.of("agentMode", AgentMode.PLAN))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("AgentMode.PLAN");
    }

    @Test
    void unknownBashAsksEvenWhenBypassWouldAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
    }

    @Test
    void strictAutoReviewDoesNotOverrideBashRiskAsk() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "bash -c \"$(cat script.sh)\"")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.BASH_RISK);
        assertThat(decision.metadata()).containsKey("bashRisk");
    }

    @Test
    void lowAndMediumBashDefaultToAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(pipeline, "pwd", PermissionBehavior.ALLOW, BashRiskLevel.LOW);
        assertBashDecision(pipeline, "touch output.txt", PermissionBehavior.ALLOW, BashRiskLevel.MEDIUM);
    }

    @Test
    void staticDiagnosticPipelineAndQuotedPythonHeredocDefaultToAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(
            pipeline,
            "grep -RInE 'class Nominal|class Categorical|margin' seaborn tests | head -200",
            PermissionBehavior.ALLOW,
            BashRiskLevel.MEDIUM
        );
        assertBashDecision(
            pipeline,
            "python - <<'PY'\nprint('literal | > $(ignored) rm -rf target')\nPY\n",
            PermissionBehavior.ALLOW,
            BashRiskLevel.MEDIUM
        );
    }

    @Test
    void heredocShellSinksAndUnsupportedFormsDefaultToAsk() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(
            pipeline,
            "cat <<'EOF' | sh\necho unsafe\nEOF\n",
            PermissionBehavior.ASK,
            BashRiskLevel.UNKNOWN
        );
        assertBashDecision(
            pipeline,
            "cat <<EOF\necho $PATH\nEOF\n",
            PermissionBehavior.ASK,
            BashRiskLevel.UNKNOWN
        );
    }

    @Test
    void listedHighAndDestructiveBashDefaultToAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(pipeline, "curl https://example.com", PermissionBehavior.ALLOW, BashRiskLevel.HIGH);
        assertBashDecision(pipeline, "rm -rf build", PermissionBehavior.ALLOW, BashRiskLevel.DESTRUCTIVE);
    }

    @Test
    void reviewOnlyBashDefaultsToAsk() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(pipeline, "dd if=a of=b", PermissionBehavior.ASK, BashRiskLevel.DESTRUCTIVE);
        assertBashDecision(pipeline, "mkfs /dev/loop0", PermissionBehavior.ASK, BashRiskLevel.DESTRUCTIVE);
        assertBashDecision(pipeline, "npm install", PermissionBehavior.ASK, BashRiskLevel.HIGH);
        assertBashDecision(pipeline, "pip install package", PermissionBehavior.ASK, BashRiskLevel.HIGH);
    }

    @Test
    void mixedBashUsesStrictestSegment() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        assertBashDecision(
            pipeline,
            "curl https://example.com && rm -rf build",
            PermissionBehavior.ALLOW,
            BashRiskLevel.DESTRUCTIVE
        );
        assertBashDecision(
            pipeline,
            "rm -rf build && dd if=a of=b",
            PermissionBehavior.ASK,
            BashRiskLevel.DESTRUCTIVE
        );
    }

    @Test
    void unknownBashDefaultsToAsk() {
        assertBashDecision(
            new PermissionDecisionPipeline(),
            "echo $(id)",
            PermissionBehavior.ASK,
            BashRiskLevel.UNKNOWN
        );
    }

    @Test
    void eligibleBashStillHonorsExplicitAskAndStrictAutoReview() {
        PermissionDecisionPipeline explicitAskPipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ASK, "bash", "curl *", "review curl")
        ));

        for (ToolUseContext context : permissionContexts(PermissionMode.ASK)) {
            PermissionDecision explicitAsk = explicitAskPipeline.decide(
                request("bash", Map.of("command", "curl https://example.com")),
                context
            );
            assertThat(explicitAsk.behavior()).isEqualTo(PermissionBehavior.ASK);
            assertThat(explicitAsk.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
            assertThat(explicitAsk.metadata()).containsKey("bashRisk");
        }

        for (ToolUseContext context : permissionContexts(PermissionMode.AUTO)) {
            java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(context.metadata());
            metadata.put("strictAutoReview", true);
            PermissionDecision strictReview = new PermissionDecisionPipeline().decide(
                request("bash", Map.of("command", "pwd")),
                new ToolUseContext(context.sessionId(), context.messageId(), context.cwd(), Map.copyOf(metadata))
            );
            assertThat(strictReview.behavior()).isEqualTo(PermissionBehavior.ASK);
            assertThat(strictReview.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
            assertThat(strictReview.metadata()).containsKeys("strictAutoReview", "bashRisk");
        }
    }

    @Test
    void explicitDenyRunsBeforeOtherReviewStages() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:git status", "allow status"),
            rule(PermissionBehavior.DENY, "bash", "git status *", "deny status")
        ));

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git status --short")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.EXPLICIT_RULE);
    }

    @Test
    void strictAutoReviewRunsBeforeStoredPrefixAllow() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline(List.of(
            rule(PermissionBehavior.ALLOW, "bash", "prefix:git status", "allow status")
        ));

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git status --short")),
            context(PermissionMode.BYPASS, Map.of("strictAutoReview", true))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(decision.message()).contains("strictAutoReview");
    }

    @Test
    void permissionRuntimeStateMetadataSupersedesLegacyPermissionModeMetadata() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(
                PermissionMode.ASK,
                Map.of("permissionRuntimeState", PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS))
            )
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void permissionRuntimeStateBehaviorSupersedesLegacyModeForBashRiskDecision() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();
        PermissionRuntimeState bypassBehaviorWithDefaultLegacyMode = runtimeStateWithLegacyMode(
            PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS),
            PermissionMode.ASK
        );

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "git push origin feature/security")),
            context(
                PermissionMode.ASK,
                Map.of("permissionRuntimeState", bypassBehaviorWithDefaultLegacyMode)
            )
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void additionalFilesystemPermissionsAllowApprovedWriteOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.ASK, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void dangerFullAccessRuntimeStateDoesNotUseLegacyCwdBoundaryAsAuthority() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/outside-workspace/out.txt")),
            context(
                PermissionMode.ASK,
                Map.of("permissionRuntimeState", PermissionRuntimeState.fromLegacy(PermissionMode.BYPASS))
            )
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void customRuntimeProfileAllowsWriteOutsideWorkspaceWhenProfileAllowsPath() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/tmp/cache/a.txt")),
            context(PermissionMode.ASK, Map.of(
                "permissionRuntimeState",
                runtimeStateWithProfile("dev", managedProfile("/tmp/cache", FileSystemAccessMode.WRITE))
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void customRuntimeProfileDeniesWriteOutsideWorkspaceWhenProfileDoesNotAllowPath() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/tmp/cache/a.txt")),
            context(PermissionMode.ASK, Map.of(
                "permissionRuntimeState",
                runtimeStateWithProfile("dev", PermissionProfiles.readOnly())
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void unapprovedAdditionalFilesystemPermissionsDoNotAllowWriteOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.ASK, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE)
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void additionalFilesystemPermissionsDoNotConsumeWidePoliciesInPipeline() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision unrestricted = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.ASK, Map.of(
                "additionalPermissions",
                new AdditionalPermissionProfile(Optional.of(FileSystemPermissionPolicy.unrestricted()), Optional.empty()),
                "approvedAdditionalPermissions",
                true
            ))
        );
        PermissionDecision specialRoot = pipeline.decide(
            request("write", Map.of("path", "/approved/outside.txt")),
            context(PermissionMode.ASK, Map.of(
                "additionalPermissions",
                additionalRootFileSystem(FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(unrestricted.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(unrestricted.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
        assertThat(specialRoot.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(specialRoot.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    @Test
    void additionalFilesystemPermissionsAllowApprovedBashRedirectOutsideWorkspace() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "echo ok > /approved/output.txt")),
            context(PermissionMode.BYPASS, Map.of(
                "additionalPermissions",
                additionalFileSystem("/approved", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void bashCwdSkipsHostSidePathSafety(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".git"));
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "pwd", "cwd", ".git")),
            context(PermissionMode.ASK, workspace, Map.of())
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void bashRedirectSkipsHostSideFilesystemProfile() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "printf x > /etc/lypi-test")),
            context(PermissionMode.ASK)
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
        assertThat(decision.metadata()).containsKey("bashRisk");
    }

    @Test
    void additionalFilesystemPermissionsDoNotBypassHardSafety() {
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("edit", Map.of("path", ".git/config")),
            context(PermissionMode.BYPASS, Map.of(
                "additionalPermissions",
                additionalFileSystem(".git", FileSystemAccessMode.WRITE),
                "approvedAdditionalPermissions",
                true
            ))
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
    }

    @Test
    void bashRedirectSymlinkSkipsHostSidePathSafety(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Path approved = tempDir.resolve("approved");
        Files.createDirectories(workspace.resolve(".git"));
        Files.createDirectories(approved);
        Files.createSymbolicLink(approved.resolve("git-link"), workspace.resolve(".git"));
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();

        PermissionDecision decision = pipeline.decide(
            request("bash", Map.of("command", "echo ok > " + approved.resolve("git-link/config"))),
            context(PermissionMode.ASK, workspace, Map.of())
        );

        assertThat(decision.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(decision.reason()).isEqualTo(PermissionDecisionReason.MODE_DEFAULT);
    }

    @Test
    void nonBashFileToolsKeepPathSafetyAndFilesystemProfileChecks(@TempDir Path tempDir) throws IOException {
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(workspace.resolve(".git"));
        PermissionDecisionPipeline pipeline = new PermissionDecisionPipeline();
        ToolUseContext readOnlyContext = context(
            PermissionMode.ASK,
            workspace,
            Map.of("permissionRuntimeState", runtimeStateWithProfile("read-only", PermissionProfiles.readOnly()))
        );

        PermissionDecision read = pipeline.decide(
            request("read", Map.of("path", ".git/config")),
            readOnlyContext
        );
        PermissionDecision edit = pipeline.decide(
            request("edit", Map.of("path", ".git/config")),
            readOnlyContext
        );
        PermissionDecision write = pipeline.decide(
            request("write", Map.of("path", workspace.resolve("output.txt").toString())),
            readOnlyContext
        );

        assertThat(read.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
        assertThat(edit.reason()).isEqualTo(PermissionDecisionReason.HARD_SAFETY);
        assertThat(write.behavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(write.reason()).isEqualTo(PermissionDecisionReason.SANDBOX_POLICY);
    }

    private ToolUseRequest request(String toolName, Map<String, Object> input) {
        return new ToolUseRequest("toolu_1", toolName, input, "msg_1");
    }

    private void assertBashDecision(
        PermissionDecisionPipeline pipeline,
        String command,
        PermissionBehavior expectedBehavior,
        BashRiskLevel expectedRisk
    ) {
        for (PermissionMode mode : List.of(PermissionMode.ASK, PermissionMode.AUTO)) {
            for (ToolUseContext context : permissionContexts(mode)) {
                PermissionDecision decision = pipeline.decide(
                    request("bash", Map.of("command", command)),
                    context
                );

                assertThat(decision.behavior()).as(mode + ": " + command).isEqualTo(expectedBehavior);
                assertThat(decision.metadata().get("bashRisk"))
                    .as(mode + ": " + command)
                    .isInstanceOf(BashRiskAnalysis.class);
                assertThat(((BashRiskAnalysis) decision.metadata().get("bashRisk")).riskLevel())
                    .as(mode + ": " + command)
                    .isEqualTo(expectedRisk);
            }
        }
    }

    private List<ToolUseContext> permissionContexts(PermissionMode mode) {
        return List.of(
            new ToolUseContext(
                "ses_canonical",
                "msg_1",
                Path.of("/workspace"),
                Map.of("permissionRuntimeState", PermissionRuntimeState.forMode(mode))
            ),
            context(mode)
        );
    }

    private ToolUseContext context(PermissionMode mode) {
        return context(mode, Map.of());
    }

    private ToolUseContext context(PermissionMode mode, Map<String, Object> extraMetadata) {
        return context(mode, Path.of("/workspace"), extraMetadata);
    }

    private ToolUseContext context(PermissionMode mode, Path cwd, Map<String, Object> extraMetadata) {
        java.util.LinkedHashMap<String, Object> metadata = new java.util.LinkedHashMap<>(extraMetadata);
        metadata.put("permissionMode", mode);
        return new ToolUseContext("ses_1", "msg_1", cwd, metadata);
    }

    private PermissionRule rule(PermissionBehavior behavior, String toolName, String pattern, String reason) {
        return new PermissionRule(
            PermissionRuleSource.SESSION,
            behavior,
            new PermissionRuleValue(toolName, pattern),
            reason
        );
    }

    private AdditionalPermissionProfile additionalFileSystem(String path, FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.exactPath(path), accessMode)
            ))),
            Optional.empty()
        );
    }

    private AdditionalPermissionProfile additionalRootFileSystem(FileSystemAccessMode accessMode) {
        return new AdditionalPermissionProfile(
            Optional.of(FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), accessMode)
            ))),
            Optional.empty()
        );
    }

    private PermissionRuntimeState runtimeStateWithLegacyMode(PermissionRuntimeState source, PermissionMode legacyMode) {
        return new PermissionRuntimeState(
            source.approvalPolicy(),
            source.activePermissionProfile(),
            source.permissionProfile(),
            source.legacyBehavior(),
            legacyMode
        );
    }

    private PermissionRuntimeState runtimeStateWithProfile(String id, ManagedPermissionProfile profile) {
        return new PermissionRuntimeState(
            ApprovalPolicy.fromLegacy(PermissionMode.ASK),
            new ActivePermissionProfile(id),
            profile,
            new LegacyPermissionBehavior(false, false, true),
            PermissionMode.ASK
        );
    }

    private ManagedPermissionProfile managedProfile(String path, FileSystemAccessMode accessMode) {
        return new ManagedPermissionProfile(
            FileSystemPermissionPolicy.restricted(List.of(
                new FileSystemPermissionEntry(FileSystemPath.special(FileSystemSpecialPath.ROOT), FileSystemAccessMode.READ),
                new FileSystemPermissionEntry(FileSystemPath.exactPath(path), accessMode)
            )),
            cn.lypi.contracts.security.NetworkPermissionPolicy.restricted()
        );
    }
}
