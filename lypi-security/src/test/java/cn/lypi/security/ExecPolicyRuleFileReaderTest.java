package cn.lypi.security;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionRuleValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExecPolicyRuleFileReaderTest {
    @TempDir
    private Path tempDir;

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
}
