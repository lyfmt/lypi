package cn.lycode.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BashCommandNormalizerTest {
    private final BashCommandNormalizer normalizer = new BashCommandNormalizer();

    @Test
    void keepsOperatorsInsideQuotedArguments() {
        String command = "grep -RInE 'class Nominal|class Categorical|margin' seaborn tests | head -200";

        assertThat(normalizer.scan(command).segments()).containsExactly(
            "grep -RInE 'class Nominal|class Categorical|margin' seaborn tests",
            "head -200"
        );
    }

    @Test
    void splitsOnlyUnquotedAndUnescapedOperators() {
        String command = "printf \"a|b&&c\" && echo one\\|two; pwd\nls & wc -l || cat file";

        assertThat(normalizer.scan(command).segments()).containsExactly(
            "printf \"a|b&&c\"",
            "echo one\\|two",
            "pwd",
            "ls",
            "wc -l",
            "cat file"
        );
    }

    @Test
    void marksUnclosedQuotesAndEscapesAsAmbiguous() {
        for (String command : List.of("echo 'unterminated", "echo \"unterminated", "echo trailing\\")) {
            assertThat(normalizer.scan(command).ambiguous()).as(command).isTrue();
        }
    }

    @Test
    void removesStrictQuotedHeredocBodiesFromAnalysis() {
        String command = """
            pytest -q && python - <<'PY'
            value = 'class Nominal|margin'
            print('literal > output $(ignored) rm -rf target')
            PY
            rg done
            """;

        BashCommandNormalizer.CommandScan scan = normalizer.scan(command);

        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.segments()).containsExactly("pytest -q", "python -", "rg done");
        assertThat(scan.analyzableCommand()).doesNotContain("literal > output", "$(ignored)", "rm -rf target");
    }

    @Test
    void keepsShellSinkAfterStrictQuotedHeredocVisible() {
        String command = """
            cat <<'EOF' | sh
            echo unsafe
            EOF
            """;

        BashCommandNormalizer.CommandScan scan = normalizer.scan(command);

        assertThat(scan.ambiguous()).isFalse();
        assertThat(scan.segments()).containsExactly("cat", "sh");
    }

    @Test
    void rejectsUnsupportedOrIncompleteHeredocs() {
        for (String command : List.of(
            "cat <<EOF\necho $PATH\nEOF\n",
            "cat <<'EOF'\nmissing terminator\n",
            "cat <<'$TOKEN'\nvalue\n$TOKEN\n",
            "cat <<-'EOF'\nvalue\nEOF\n",
            "cat <<'ONE' <<'TWO'\none\nONE\ntwo\nTWO\n"
        )) {
            assertThat(normalizer.scan(command).ambiguous()).as(command).isTrue();
        }
    }
}
