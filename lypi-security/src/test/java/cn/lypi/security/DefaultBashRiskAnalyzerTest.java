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
}
