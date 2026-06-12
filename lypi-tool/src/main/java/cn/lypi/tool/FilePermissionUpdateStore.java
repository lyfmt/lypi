package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionUpdate;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 将 exec policy 权限更新追加到 runtime 规则文件。
 */
public final class FilePermissionUpdateStore implements PermissionUpdateStore {
    private static final String PREFIX_PATTERN = "prefix:";

    private final Path rulesFile;

    public FilePermissionUpdateStore(Path runtimeConfigDir) {
        Path root = runtimeConfigDir == null ? Path.of(".") : runtimeConfigDir;
        this.rulesFile = root.resolve("rules").resolve("default.rules");
    }

    @Override
    public void append(PermissionUpdate update) {
        if (!isSupportedPrefixUpdate(update)) {
            return;
        }
        PermissionRule rule = update.rule();
        List<String> tokens = List.of(rule.value().pattern().substring(PREFIX_PATTERN.length()).split("\\s+"));
        String line = "prefix_rule(pattern=["
            + tokens.stream()
                .map(this::quote)
                .reduce((left, right) -> left + ", " + right)
                .orElse("")
            + "], decision=\"allow\")\n";
        try {
            Files.createDirectories(rulesFile.getParent());
            Files.writeString(
                rulesFile,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("权限规则写入失败: " + exception.getMessage(), exception);
        }
    }

    private boolean isSupportedPrefixUpdate(PermissionUpdate update) {
        if (update == null || update.rule() == null || update.rule().value() == null) {
            return false;
        }
        PermissionRule rule = update.rule();
        return rule.behavior() == PermissionBehavior.ALLOW
            && "bash".equals(rule.value().toolName())
            && rule.value().pattern() != null
            && rule.value().pattern().startsWith(PREFIX_PATTERN);
    }

    private String quote(String token) {
        return "\"" + token.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
