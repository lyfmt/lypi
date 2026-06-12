package cn.lypi.tool;

import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.tool.ToolUseRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 保存当前 JVM 进程内的 Bash 前缀白名单。
 *
 * NOTE: 该白名单不持久化，只接受 SESSION 级 Bash ALLOW 规则。
 */
final class RuntimeBashPrefixAllowlist {
    private final CopyOnWriteArrayList<List<String>> prefixes = new CopyOnWriteArrayList<>();

    /**
     * 记住当前运行期可复用的 Bash token 前缀。
     */
    void rememberForRuntime(List<String> prefix) {
        List<String> normalized = normalizePrefix(prefix);
        if (normalized.isEmpty()) {
            return;
        }
        if (!prefixes.contains(normalized)) {
            prefixes.add(normalized);
        }
    }

    /**
     * 从权限更新中提取 Bash 前缀并写入运行期白名单。
     */
    void rememberForRuntime(PermissionUpdate update) {
        if (update == null || update.targetSource() != PermissionRuleSource.SESSION) {
            return;
        }
        PermissionRule rule = update.rule();
        if (rule == null
            || rule.behavior() != PermissionBehavior.ALLOW
            || rule.value() == null
            || !"bash".equals(rule.value().toolName())) {
            return;
        }
        rememberForRuntime(prefixFromPattern(rule.value().pattern()));
    }

    /**
     * 判断请求是否匹配任一运行期 Bash 前缀。
     */
    boolean matches(ToolUseRequest request) {
        if (request == null || !"bash".equals(request.toolName())) {
            return false;
        }
        List<List<String>> segments = commandSegments(commandInput(request));
        if (segments.isEmpty()) {
            return false;
        }
        for (List<String> prefix : prefixes) {
            if (allSegmentsStartWith(segments, prefix)) {
                return true;
            }
        }
        return false;
    }

    private List<String> prefixFromPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return List.of();
        }
        String normalized = pattern.trim();
        if (normalized.endsWith(" *")) {
            normalized = normalized.substring(0, normalized.length() - 2).trim();
        }
        if (normalized.contains("*")) {
            return List.of();
        }
        return words(normalized);
    }

    private List<String> normalizePrefix(List<String> prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String token : prefix) {
            if (token == null || token.isBlank()) {
                return List.of();
            }
            normalized.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private boolean startsWith(List<String> words, List<String> prefix) {
        if (words.size() < prefix.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!words.get(index).equals(prefix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private boolean allSegmentsStartWith(List<List<String>> segments, List<String> prefix) {
        for (List<String> segment : segments) {
            if (!startsWith(segment, prefix)) {
                return false;
            }
        }
        return true;
    }

    private List<List<String>> commandSegments(String command) {
        if (command == null || command.isBlank()) {
            return List.of();
        }
        List<List<String>> segments = new ArrayList<>();
        for (String segment : command.trim().split("\\s*(?:&&|\\|\\||;|\\n|(?<![&])&(?!&)|(?<!\\|)\\|(?!\\|))\\s*")) {
            List<String> words = words(segment);
            if (!words.isEmpty()) {
                segments.add(words);
            }
        }
        return List.copyOf(segments);
    }

    private List<String> words(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> words = new ArrayList<>();
        for (String word : value.trim().split("\\s+")) {
            if (!word.isBlank()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(words);
    }

    private String commandInput(ToolUseRequest request) {
        Object command = request.input() == null ? null : request.input().get("command");
        if (command == null && request.input() != null) {
            command = request.input().get("cmd");
        }
        return command == null ? "" : command.toString();
    }
}
