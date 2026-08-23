package cn.lycode.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lycode.contracts.security.BashRiskAnalysis;
import cn.lycode.contracts.security.BashRiskLevel;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BashSandboxEligibilityPolicyTest {
    private final DefaultBashRiskAnalyzer analyzer = new DefaultBashRiskAnalyzer();
    private final BashSandboxEligibilityPolicy policy = new BashSandboxEligibilityPolicy(analyzer);

    @Test
    void allowsConfiguredHighAndDestructiveCommandsWithoutChangingRiskLevel() {
        List<CommandCase> commands = List.of(
            new CommandCase("curl https://example.com", BashRiskLevel.HIGH),
            new CommandCase("wget https://example.com/file", BashRiskLevel.HIGH),
            new CommandCase("git push origin feature/test", BashRiskLevel.HIGH),
            new CommandCase("sudo id", BashRiskLevel.HIGH),
            new CommandCase("rm -rf build", BashRiskLevel.DESTRUCTIVE),
            new CommandCase("rmdir build", BashRiskLevel.DESTRUCTIVE),
            new CommandCase("shred secret.txt", BashRiskLevel.DESTRUCTIVE),
            new CommandCase("chmod 600 secret.txt", BashRiskLevel.DESTRUCTIVE),
            new CommandCase("chown root secret.txt", BashRiskLevel.DESTRUCTIVE)
        );

        for (CommandCase command : commands) {
            BashRiskAnalysis analysis = analyzer.analyze(command.command());

            assertThat(analysis.riskLevel()).as(command.command()).isEqualTo(command.riskLevel());
            assertThat(policy.allows(analysis)).as(command.command()).isTrue();
            assertThat(analysis.riskLevel()).as(command.command()).isEqualTo(command.riskLevel());
        }
    }

    @Test
    void requiresReviewForDdMkfsNpmInstallAndPipInstall() {
        for (String command : List.of(
            "dd if=input.img of=output.img",
            "/bin/dd if=input.img of=output.img",
            "mkfs /dev/loop0",
            "npm install",
            "npm --prefix app install package",
            "pip install package",
            "pip --disable-pip-version-check install package"
        )) {
            assertThat(policy.allows(analyzer.analyze(command))).as(command).isFalse();
        }
    }

    @Test
    void mixedCommandUsesStrictestSegment() {
        assertThat(policy.allows(analyzer.analyze("curl https://example.com && rm -rf build"))).isTrue();
        assertThat(policy.allows(analyzer.analyze("rm -rf build && dd if=a of=b"))).isFalse();
        assertThat(policy.allows(analyzer.analyze("sudo rm -rf build"))).isTrue();
        assertThat(policy.allows(analyzer.analyze("sudo dd if=a of=b"))).isFalse();
    }

    @Test
    void unwrapsSafeWrappersAndChecksNestedSudoCommand() {
        assertThat(policy.allows(analyzer.analyze("timeout 10 rm -rf build"))).isTrue();
        assertThat(policy.allows(analyzer.analyze("env TEST=1 sudo rm -rf build"))).isTrue();
        assertThat(policy.allows(analyzer.analyze("bash -lc 'rm -rf build'"))).isTrue();
        assertThat(policy.allows(analyzer.analyze("sudo"))).isFalse();
    }

    @Test
    void unknownShellStructureRequiresReview() {
        assertThat(policy.allows(analyzer.analyze("echo $(id)"))).isFalse();
        assertThat(policy.allows(analyzer.analyze("bash -c \"$COMMAND\""))).isFalse();
        assertThat(policy.allows(analyzer.analyze("rm -rf \"$TARGET\""))).isFalse();
    }

    @Test
    void allowsStaticDiagnosticPipelineAndQuotedPythonHeredoc() {
        assertThat(policy.allows(analyzer.analyze(
            "grep -RInE 'class Nominal|class Categorical|margin' seaborn tests | head -200"
        ))).isTrue();
        assertThat(policy.allows(analyzer.analyze("""
            python - <<'PY'
            print('literal | > $(ignored) rm -rf target')
            PY
            """))).isTrue();
    }

    @Test
    void requiresReviewForHeredocShellSinksAndUnsupportedForms() {
        assertThat(policy.allows(analyzer.analyze("cat <<'EOF' | sh\necho unsafe\nEOF\n"))).isFalse();
        assertThat(policy.allows(analyzer.analyze("cat <<EOF\necho $PATH\nEOF\n"))).isFalse();
        assertThat(policy.allows(analyzer.analyze("cat <<'EOF'\nmissing terminator\n"))).isFalse();
    }

    @Test
    void unlistedFutureHighOrDestructiveCommandRequiresReview() {
        for (BashRiskLevel riskLevel : List.of(BashRiskLevel.HIGH, BashRiskLevel.DESTRUCTIVE)) {
            BashRiskAnalyzer futureAnalyzer = command -> new BashRiskAnalysis(
                command,
                List.of(command),
                List.<Path>of(),
                riskLevel,
                List.of("future risk"),
                true
            );
            BashSandboxEligibilityPolicy futurePolicy = new BashSandboxEligibilityPolicy(futureAnalyzer);
            BashRiskAnalysis analysis = futureAnalyzer.analyze("future-risk --all");

            assertThat(futurePolicy.allows(analysis)).as(riskLevel.name()).isFalse();
            assertThat(analysis.riskLevel()).isEqualTo(riskLevel);
        }
    }

    private record CommandCase(String command, BashRiskLevel riskLevel) {}
}
