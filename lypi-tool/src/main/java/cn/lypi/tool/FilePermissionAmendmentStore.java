package cn.lypi.tool;

import cn.lypi.contracts.security.NetworkPolicyAmendment;
import cn.lypi.contracts.security.PermissionAmendment;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionRule;
import cn.lypi.contracts.security.PermissionRuleSource;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.session.PermissionAmendmentEntry;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 使用 `.ly-pi/permissions.jsonl` 持久化权限修订。
 */
public final class FilePermissionAmendmentStore implements PermissionAmendmentStore, PermissionUpdateStore {
    private static final String PREFIX_PATTERN = "prefix:";
    private static final List<List<String>> BANNED_PREFIXES = List.of(
        List.of("bash", "-lc"),
        List.of("bash"),
        List.of("sh"),
        List.of("sh", "-c"),
        List.of("zsh"),
        List.of("zsh", "-c"),
        List.of("python"),
        List.of("python3"),
        List.of("git")
    );

    private final Path amendmentsFile;
    private final ObjectMapper jsonMapper;

    public FilePermissionAmendmentStore(Path runtimeConfigDir) {
        Path root = runtimeConfigDir == null ? Path.of(".") : runtimeConfigDir;
        this.amendmentsFile = root.resolve(".ly-pi").resolve("permissions.jsonl");
        this.jsonMapper = new ObjectMapper()
            .registerModule(new Jdk8Module());
    }

    @Override
    public void append(PermissionUpdate update) {
        appendPermissionUpdate(update, PermissionGrantScope.SESSION);
    }

    @Override
    public void appendPermissionUpdate(PermissionUpdate update, PermissionGrantScope scope) {
        appendPermissionUpdate(update, scope, null);
    }

    @Override
    public void appendPermissionUpdate(PermissionUpdate update, PermissionGrantScope scope, String sessionId) {
        appendPermissionUpdate(update, scope, sessionId, null);
    }

    @Override
    public void appendPermissionUpdate(
        PermissionUpdate update,
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        validatePrefixUpdate(update);
        append(new StoredPermissionAmendment(
            normalizeScope(scope),
            normalizeSessionId(sessionId),
            normalizeTurnId(turnId),
            entryId(),
            null,
            Optional.of(update),
            Optional.empty(),
            Instant.now().toString()
        ));
    }

    @Override
    public void appendNetworkPolicyAmendment(NetworkPolicyAmendment amendment, PermissionGrantScope scope) {
        appendNetworkPolicyAmendment(amendment, scope, null);
    }

    @Override
    public void appendNetworkPolicyAmendment(
        NetworkPolicyAmendment amendment,
        PermissionGrantScope scope,
        String sessionId
    ) {
        appendNetworkPolicyAmendment(amendment, scope, sessionId, null);
    }

    @Override
    public void appendNetworkPolicyAmendment(
        NetworkPolicyAmendment amendment,
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        if (amendment == null) {
            throw new IllegalArgumentException("network policy amendment must not be null");
        }
        append(new StoredPermissionAmendment(
            normalizeScope(scope),
            normalizeSessionId(sessionId),
            normalizeTurnId(turnId),
            entryId(),
            null,
            Optional.empty(),
            Optional.of(amendment),
            Instant.now().toString()
        ));
    }

    @Override
    public List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope) {
        return readPermissionUpdates(scope, null);
    }

    @Override
    public List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope, String sessionId) {
        return readPermissionUpdates(scope, sessionId, null);
    }

    @Override
    public List<PermissionUpdate> readPermissionUpdates(PermissionGrantScope scope, String sessionId, String turnId) {
        PermissionGrantScope normalizedScope = normalizeScope(scope);
        String normalizedSessionId = normalizeSessionId(sessionId);
        String normalizedTurnId = normalizeTurnId(turnId);
        List<PermissionUpdate> updates = new ArrayList<>();
        for (StoredPermissionAmendment amendment : readAmendments()) {
            if (matchesScopeSessionAndTurn(amendment, normalizedScope, normalizedSessionId, normalizedTurnId)) {
                amendment.entry().permissionUpdate().ifPresent(updates::add);
            }
        }
        return List.copyOf(updates);
    }

    @Override
    public List<NetworkPolicyAmendment> readNetworkPolicyAmendments(PermissionGrantScope scope) {
        return readNetworkPolicyAmendments(scope, null);
    }

    @Override
    public List<NetworkPolicyAmendment> readNetworkPolicyAmendments(PermissionGrantScope scope, String sessionId) {
        return readNetworkPolicyAmendments(scope, sessionId, null);
    }

    @Override
    public List<NetworkPolicyAmendment> readNetworkPolicyAmendments(
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        PermissionGrantScope normalizedScope = normalizeScope(scope);
        String normalizedSessionId = normalizeSessionId(sessionId);
        String normalizedTurnId = normalizeTurnId(turnId);
        List<NetworkPolicyAmendment> amendments = new ArrayList<>();
        for (StoredPermissionAmendment amendment : readAmendments()) {
            if (matchesScopeSessionAndTurn(amendment, normalizedScope, normalizedSessionId, normalizedTurnId)) {
                amendment.entry().networkPolicyAmendment().ifPresent(amendments::add);
            }
        }
        return List.copyOf(amendments);
    }

    private void append(StoredPermissionAmendment amendment) {
        try {
            Files.createDirectories(amendmentsFile.getParent());
            Files.writeString(
                amendmentsFile,
                jsonMapper.writeValueAsString(amendment) + "\n",
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException exception) {
            throw new IllegalStateException("权限修订写入失败: " + exception.getMessage(), exception);
        }
    }

    private List<StoredPermissionAmendment> readAmendments() {
        if (!Files.isRegularFile(amendmentsFile)) {
            return List.of();
        }
        try {
            List<StoredPermissionAmendment> amendments = new ArrayList<>();
            for (String line : Files.readAllLines(amendmentsFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                StoredPermissionAmendment amendment = jsonMapper.readValue(line, StoredPermissionAmendment.class);
                validateStoredAmendment(amendment);
                amendments.add(amendment);
            }
            return List.copyOf(amendments);
        } catch (IOException exception) {
            throw new IllegalStateException("权限修订读取失败: " + exception.getMessage(), exception);
        }
    }

    private void validatePrefixUpdate(PermissionUpdate update) {
        if (!isSupportedPrefixUpdate(update)) {
            throw new IllegalArgumentException("unsupported permission amendment");
        }
    }

    private void validateStoredAmendment(StoredPermissionAmendment amendment) {
        amendment.entry();
        amendment.permissionUpdate().ifPresent(this::validatePrefixUpdate);
        amendment.networkPolicyAmendment().ifPresent(networkAmendment -> {
            if (networkAmendment.networkPolicy() == null) {
                throw new IllegalArgumentException("invalid network policy amendment");
            }
        });
    }

    private boolean isSupportedPrefixUpdate(PermissionUpdate update) {
        if (update == null || update.rule() == null || update.rule().value() == null) {
            return false;
        }
        PermissionRule rule = update.rule();
        return update.targetSource() == rule.source()
            && (update.targetSource() == PermissionRuleSource.USER
                || update.targetSource() == PermissionRuleSource.PROJECT
                || update.targetSource() == PermissionRuleSource.SESSION)
            && rule.behavior() == PermissionBehavior.ALLOW
            && "bash".equals(rule.value().toolName())
            && rule.value().pattern() != null
            && rule.value().pattern().startsWith(PREFIX_PATTERN)
            && isSupportedPrefix(rule.value().pattern());
    }

    private PermissionGrantScope normalizeScope(PermissionGrantScope scope) {
        return scope == null ? PermissionGrantScope.TURN : scope;
    }

    private boolean isSupportedPrefix(String pattern) {
        String prefix = pattern.substring(PREFIX_PATTERN.length()).trim();
        if (prefix.isBlank()) {
            return false;
        }
        List<String> tokens = normalizeTokens(List.of(prefix.split("\\s+")));
        return tokens.size() >= 2 && !BANNED_PREFIXES.contains(tokens);
    }

    private List<String> normalizeTokens(List<String> tokens) {
        List<String> normalized = new ArrayList<>();
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            normalized.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(normalized);
    }

    private String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? null : sessionId;
    }

    private String normalizeTurnId(String turnId) {
        return turnId == null || turnId.isBlank() ? null : turnId;
    }

    private boolean matchesScopeSessionAndTurn(
        StoredPermissionAmendment amendment,
        PermissionGrantScope scope,
        String sessionId,
        String turnId
    ) {
        if (amendment.scope() != scope) {
            return false;
        }
        String amendmentSessionId = normalizeSessionId(amendment.sessionId());
        if (sessionId != null && !sessionId.equals(amendmentSessionId)) {
            return false;
        }
        String amendmentTurnId = normalizeTurnId(amendment.turnId());
        return turnId == null || turnId.equals(amendmentTurnId);
    }

    private String entryId() {
        return "perm_amend_" + UUID.randomUUID();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record StoredPermissionAmendment(
        PermissionGrantScope scope,
        String sessionId,
        String turnId,
        String id,
        String parentId,
        Optional<PermissionUpdate> permissionUpdate,
        Optional<NetworkPolicyAmendment> networkPolicyAmendment,
        String timestamp
    ) {
        private StoredPermissionAmendment {
            scope = scope == null ? PermissionGrantScope.TURN : scope;
            sessionId = sessionId == null || sessionId.isBlank() ? null : sessionId;
            turnId = turnId == null || turnId.isBlank() ? null : turnId;
            permissionUpdate = permissionUpdate == null ? Optional.empty() : permissionUpdate;
            networkPolicyAmendment = networkPolicyAmendment == null ? Optional.empty() : networkPolicyAmendment;
            timestamp = timestamp == null || timestamp.isBlank() ? Instant.EPOCH.toString() : timestamp;
        }

        private PermissionAmendmentEntry entry() {
            return new PermissionAmendmentEntry(
                id,
                parentId,
                permissionAmendment(),
                Instant.parse(timestamp)
            );
        }

        private PermissionAmendment permissionAmendment() {
            return new PermissionAmendment(permissionUpdate, networkPolicyAmendment);
        }
    }
}
