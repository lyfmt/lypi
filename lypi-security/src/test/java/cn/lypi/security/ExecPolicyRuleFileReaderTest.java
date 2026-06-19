package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecPolicyRuleFileReaderTest {
    @TempDir
    private Path tempDir;

    @Test
    void readParsesPrefixAllowRules() throws Exception {
        Path rulesFile = tempDir.resolve("default.rules");
        Files.writeString(rulesFile, "prefix_rule(pattern=[\"go\", \"test\"], decision=\"allow\")\n");

        List<PermissionRule> rules = new ExecPolicyRuleFileReader().read(rulesFile);

        assertThat(rules).hasSize(1);
        PermissionRule rule = rules.getFirst();
        assertThat(rule.source()).isEqualTo(PermissionRuleSource.USER);
        assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(rule.value().toolName()).isEqualTo("bash");
        assertThat(rule.value().pattern()).isEqualTo("prefix:go test");
    }

    @Test
    void ignoresBannedLegacyPrefixRules() throws Exception {
        Path rulesFile = tempDir.resolve("rules/default.rules");
        Files.createDirectories(rulesFile.getParent());
        Files.writeString(
            rulesFile,
            "prefix_rule(pattern=[\"mvn\", \"test\"], decision=\"allow\")\n"
                + "prefix_rule(pattern=[\"bash\", \"-lc\"], decision=\"allow\")\n"
        );

        List<PermissionRule> rules = new ExecPolicyRuleFileReader().read(rulesFile);

        assertThat(rules).containsExactly(new PermissionRule(
            PermissionRuleSource.USER,
            PermissionBehavior.ALLOW,
            new PermissionRuleValue("bash", "prefix:mvn test"),
            "exec policy file: prefix:mvn test"
        ));
    }

    @Test
    void readReturnsEmptyRulesWhenRulesFileDoesNotExist() {
        assertThat(new ExecPolicyRuleFileReader().read(tempDir.resolve("missing.rules"))).isEmpty();
    }

    @Test
    void readReturnsEmptyRulesWhenRulesFileCannotBeRead() throws Exception {
        Path rulesFile = tempDir.resolve("default.rules");
        Files.writeString(rulesFile, "prefix_rule(pattern=[\"go\", \"test\"], decision=\"allow\")\n");

        ExecPolicyRuleFileReader reader = new ExecPolicyRuleFileReader(path -> {
            throw new AccessDeniedException(path.toString());
        });

        assertThat(reader.read(rulesFile)).isEmpty();
    }
}
