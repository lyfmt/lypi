package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.BashRiskAnalysis;
import cn.lypi.contracts.security.BashRiskLevel;
import org.junit.jupiter.api.Test;

class DefaultBashRiskAnalyzerTest {
    private final BashRiskAnalyzer analyzer = new DefaultBashRiskAnalyzer();

    @Test
    void analyzeTreatsSimpleReadOnlyCommandsAsLowRisk() {
        BashRiskAnalysis analysis = analyzer.analyze("  FOO=bar git status --short  ");

        assertThat(analysis.normalizedCommand()).isEqualTo("git status --short");
        assertThat(analysis.parsedCommands()).containsExactly("git status");
        assertThat(analysis.redirectTargets()).isEmpty();
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.LOW);
        assertThat(analysis.staticallyKnown()).isTrue();
    }

    @Test
    void analyzeMarksFileMutationCommandsAsMediumRisk() {
        BashRiskAnalysis analysis = analyzer.analyze("mkdir -p build && mv source.txt build/source.txt");

        assertThat(analysis.parsedCommands()).containsExactly("mkdir -p", "mv source.txt build/source.txt");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.MEDIUM);
        assertThat(analysis.reasons()).contains("包含文件写入或移动命令");
    }

    @Test
    void analyzeMarksDestructiveCommandsAsDestructiveRisk() {
        BashRiskAnalysis analysis = analyzer.analyze("rm -rf target");

        assertThat(analysis.parsedCommands()).containsExactly("rm -rf target");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(analysis.reasons()).contains("包含破坏性命令");
    }

    @Test
    void analyzeClassifiesNewlineSeparatedCommandsIndependently() {
        BashRiskAnalysis analysis = analyzer.analyze("git status\nrm -rf target");

        assertThat(analysis.parsedCommands()).containsExactly("git status", "rm -rf target");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
    }

    @Test
    void analyzeClassifiesBackgroundOperatorSegmentsIndependently() {
        BashRiskAnalysis analysis = analyzer.analyze("git status & rm -rf target");

        assertThat(analysis.parsedCommands()).containsExactly("git status", "rm -rf target");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
    }

    @Test
    void analyzeStripsSafeWrappersBeforeClassifyingCommandRisk() {
        BashRiskAnalysis analysis = analyzer.analyze("FOO=bar timeout 5 nice git status --short");

        assertThat(analysis.normalizedCommand()).isEqualTo("timeout 5 nice git status --short");
        assertThat(analysis.parsedCommands()).containsExactly("git status");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.LOW);
    }

    @Test
    void analyzeStillFindsDestructiveCommandBehindSafeWrappers() {
        BashRiskAnalysis analysis = analyzer.analyze("timeout 10 env FOO=bar rm -rf target");

        assertThat(analysis.parsedCommands()).containsExactly("rm -rf target");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
    }

    @Test
    void analyzeStillFindsDestructiveCommandBehindWrapperOptions() {
        BashRiskAnalysis nice = analyzer.analyze("nice -n 10 rm -rf target");
        BashRiskAnalysis env = analyzer.analyze("env -i rm -rf target");
        BashRiskAnalysis time = analyzer.analyze("time -p rm -rf target");
        BashRiskAnalysis envChdir = analyzer.analyze("env -C /tmp rm -rf target");
        BashRiskAnalysis envSplitString = analyzer.analyze("env -S ignored rm -rf target");

        assertThat(nice.parsedCommands()).containsExactly("rm -rf target");
        assertThat(nice.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(env.parsedCommands()).containsExactly("rm -rf target");
        assertThat(env.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(time.parsedCommands()).containsExactly("rm -rf target");
        assertThat(time.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(envChdir.parsedCommands()).containsExactly("rm -rf target");
        assertThat(envChdir.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(envSplitString.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(envSplitString.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeMarksDynamicShellFeaturesAsUnknown() {
        BashRiskAnalysis analysis = analyzer.analyze("bash -c \"$(cat script.sh)\"");

        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(analysis.staticallyKnown()).isFalse();
        assertThat(analysis.reasons()).contains("包含动态 shell 结构");
    }

    @Test
    void analyzeCollectsRedirectTargetsAndRaisesWriteRisk() {
        BashRiskAnalysis analysis = analyzer.analyze("echo hello > notes/output.txt");

        assertThat(analysis.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.MEDIUM);
        assertThat(analysis.reasons()).contains("包含输出重定向");
    }

    @Test
    void analyzeMarksHeredocAsUnknownWhenItCanFeedShellContent() {
        BashRiskAnalysis analysis = analyzer.analyze("cat <<EOF | sh\nrm -rf target\nEOF");

        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(analysis.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeDetectsRedirectTargetEvenWhenCommandContainsWrapper() {
        BashRiskAnalysis analysis = analyzer.analyze("timeout 5 echo hi > notes/output.txt");

        assertThat(analysis.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.MEDIUM);
    }

    @Test
    void analyzeMarksControlFlowAsUnknown() {
        BashRiskAnalysis analysis = analyzer.analyze("for file in *; do rm -rf \"$file\"; done");

        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(analysis.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeCollectsFileDescriptorRedirectTargets() {
        BashRiskAnalysis stdoutRedirect = analyzer.analyze("echo ok 1> notes/stdout.txt");
        BashRiskAnalysis stderrAppendRedirect = analyzer.analyze("make test 2>> logs/stderr.txt");
        BashRiskAnalysis customFdRedirect = analyzer.analyze("echo data 3> notes/fd3.txt");

        assertThat(stdoutRedirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/stdout.txt");
        assertThat(stderrAppendRedirect.redirectTargets()).extracting(Object::toString).containsExactly("logs/stderr.txt");
        assertThat(customFdRedirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/fd3.txt");
    }

    @Test
    void analyzeCollectsRedirectTargetsWithoutLeadingWhitespace() {
        BashRiskAnalysis compactRedirect = analyzer.analyze("echo hi>notes/output.txt");
        BashRiskAnalysis compactAppendRedirect = analyzer.analyze("printf hi>>notes/output.txt");
        BashRiskAnalysis compactFdRedirect = analyzer.analyze("echo hi 1>notes/output.txt");

        assertThat(compactRedirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
        assertThat(compactAppendRedirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
        assertThat(compactFdRedirect.redirectTargets()).extracting(Object::toString).containsExactly("notes/output.txt");
    }

    @Test
    void analyzeMarksProcessSubstitutionAsUnknown() {
        BashRiskAnalysis analysis = analyzer.analyze("cat <(rm -rf target)");

        assertThat(analysis.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(analysis.staticallyKnown()).isFalse();
        assertThat(analysis.reasons()).contains("包含动态 shell 结构");
    }

    @Test
    void analyzeMarksQuotedOrEscapedCommandNamesAsUnknown() {
        BashRiskAnalysis singleQuoted = analyzer.analyze("'rm' -rf target");
        BashRiskAnalysis splitQuoted = analyzer.analyze("r''m -rf target");
        BashRiskAnalysis backslashEscaped = analyzer.analyze("r\\m -rf target");

        assertThat(singleQuoted.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(singleQuoted.staticallyKnown()).isFalse();
        assertThat(splitQuoted.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(splitQuoted.staticallyKnown()).isFalse();
        assertThat(backslashEscaped.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(backslashEscaped.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeMarksSubshellAndBraceGroupsAsUnknown() {
        BashRiskAnalysis subshell = analyzer.analyze("(rm -rf target)");
        BashRiskAnalysis braceGroup = analyzer.analyze("{ rm -rf target; }");

        assertThat(subshell.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(subshell.staticallyKnown()).isFalse();
        assertThat(braceGroup.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(braceGroup.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeMarksEnvSplitStringAsUnknownWhenCommandIsHiddenInsideArgument() {
        BashRiskAnalysis shortOption = analyzer.analyze("env -S 'rm -rf target'");
        BashRiskAnalysis longOption = analyzer.analyze("env --split-string='rm -rf target'");

        assertThat(shortOption.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(shortOption.staticallyKnown()).isFalse();
        assertThat(longOption.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(longOption.staticallyKnown()).isFalse();
    }

    @Test
    void analyzeClassifiesEveryPipelineSegment() {
        BashRiskAnalysis shellPipe = analyzer.analyze("cat script.sh | sh");
        BashRiskAnalysis destructivePipe = analyzer.analyze("cat README.md | rm -rf target");

        assertThat(shellPipe.parsedCommands()).containsExactly("cat script.sh", "sh");
        assertThat(shellPipe.riskLevel()).isEqualTo(BashRiskLevel.UNKNOWN);
        assertThat(shellPipe.staticallyKnown()).isFalse();
        assertThat(shellPipe.reasons()).contains("管道包含 shell 执行段");

        assertThat(destructivePipe.parsedCommands()).containsExactly("cat README.md", "rm -rf target");
        assertThat(destructivePipe.riskLevel()).isEqualTo(BashRiskLevel.DESTRUCTIVE);
        assertThat(destructivePipe.reasons()).contains("包含破坏性命令");
    }
}
