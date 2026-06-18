package cn.lypi.tool.builtin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AgentMessage;
import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.ApprovalKind;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import cn.lypi.contracts.security.PermissionBehavior;
import cn.lypi.contracts.security.PermissionDecision;
import cn.lypi.contracts.security.PermissionDecisionReason;
import cn.lypi.contracts.security.PermissionGrantScope;
import cn.lypi.contracts.security.PermissionUpdate;
import cn.lypi.contracts.security.RequestPermissionProfile;
import cn.lypi.contracts.security.RequestPermissionsResponse;
import cn.lypi.contracts.tool.InterruptBehavior;
import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 模型请求当前 turn 或 session 的额外权限。
 */
public final class RequestPermissionsTool implements Tool<Map<String, Object>, RequestPermissionsResponse> {
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new Jdk8Module());
    private static final String INPUT_PERMISSIONS = "permissions";
    private static final String INPUT_REASON = "reason";
    private static final String INPUT_SCOPE = "scope";
    private static final String INPUT_STRICT_AUTO_REVIEW = "strictAutoReview";

    @Override
    public String name() {
        return "request_permissions";
    }

    @Override
    public String description() {
        return "Request additional filesystem or network permissions for the current turn or session. "
            + "The model may request permissions; the active approval policy decides whether a prompt is shown.";
    }

    @Override
    public List<String> aliases() {
        return List.of();
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of(INPUT_PERMISSIONS),
            "properties", Map.of(
                "environmentId", Map.of("type", "string"),
                INPUT_REASON, Map.of("type", "string"),
                INPUT_SCOPE, Map.of(
                    "type", "string",
                    "description", "Permission grant scope: turn for the current turn, or session for this session."
                ),
                INPUT_STRICT_AUTO_REVIEW, Map.of(
                    "type", "boolean",
                    "description", "When true, the following command should still be reviewed after these permissions are approved."
                ),
                INPUT_PERMISSIONS, Map.of(
                    "description", "Requested additional filesystem or network permissions. Supports filesystem and network deltas."
                )
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        try {
            AdditionalPermissionProfile permissions = additionalPermissions(input);
            if (isEmpty(permissions)) {
                return invalid("permissions 不能为空，必须请求 filesystem 或 network 增量权限。");
            }
            scope(input);
            return valid();
        } catch (IllegalArgumentException exception) {
            return invalid(exception.getMessage());
        }
    }

    @Override
    public PermissionDecision checkPermissions(Map<String, Object> input, ToolUseContext context) {
        AdditionalPermissionProfile additionalPermissions = additionalPermissions(input);
        return new PermissionDecision(
            PermissionBehavior.ASK,
            PermissionDecisionReason.TOOL_SPECIFIC,
            reason(input),
            Optional.<PermissionUpdate>empty(),
            Map.of(
                "approvalKind", ApprovalKind.REQUEST_PERMISSIONS,
                "additionalPermissions", additionalPermissions,
                INPUT_STRICT_AUTO_REVIEW, booleanInput(input, INPUT_STRICT_AUTO_REVIEW),
                INPUT_SCOPE, scope(input)
            )
        );
    }

    @Override
    public ToolResult<RequestPermissionsResponse> execute(
        Map<String, Object> input,
        ToolUseContext context,
        ProgressSink progress
    ) {
        RequestPermissionsResponse response = new RequestPermissionsResponse(
            new RequestPermissionProfile(additionalPermissions(input)),
            scope(input),
            booleanInput(input, INPUT_STRICT_AUTO_REVIEW)
        );
        return new ToolResult<>(
            response,
            false,
            List.of(ToolMessages.toolMessage(toolUseId(context), json(response), false)),
            Optional.empty()
        );
    }

    @Override
    public InterruptBehavior interruptBehavior() {
        return InterruptBehavior.CANCEL;
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return false;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return false;
    }

    @Override
    public int maxResultSize() {
        return 16_384;
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "request_permissions " + reason(input);
    }

    @Override
    public AgentMessage serializeForContext(RequestPermissionsResponse output) {
        return ToolMessages.serializeForContext(json(output));
    }

    private AdditionalPermissionProfile additionalPermissions(Map<String, Object> input) {
        Object value = input == null ? null : input.get(INPUT_PERMISSIONS);
        if (value instanceof RequestPermissionProfile profile) {
            return validateSupportedAdditionalPermissions(profile.additionalPermissions());
        }
        if (value instanceof AdditionalPermissionProfile profile) {
            return validateSupportedAdditionalPermissions(profile);
        }
        if (!(value instanceof Map<?, ?> permissions)) {
            return AdditionalPermissionProfile.empty();
        }
        Map<?, ?> additionalPermissions = unwrapAdditionalPermissions(permissions);
        return new AdditionalPermissionProfile(
            fileSystemPolicy(firstPresent(additionalPermissions, "fileSystem", "filesystem")),
            networkPolicy(additionalPermissions.get("network"))
        );
    }

    private Optional<FileSystemPermissionPolicy> fileSystemPolicy(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof FileSystemPermissionPolicy policy) {
            validateSupportedFileSystemPolicy(policy);
            return Optional.of(policy);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("permissions.fileSystem 必须为对象。");
        }
        FileSystemPolicyKind kind = enumValue(FileSystemPolicyKind.class, map.get("kind"), "permissions.fileSystem.kind");
        List<FileSystemPermissionEntry> entries = fileSystemEntries(map.get("entries"));
        FileSystemPermissionPolicy policy = new FileSystemPermissionPolicy(kind, entries);
        validateSupportedFileSystemPolicy(policy);
        return Optional.of(policy);
    }

    private List<FileSystemPermissionEntry> fileSystemEntries(Object value) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException("permissions.fileSystem.entries 必须为数组。");
        }
        List<FileSystemPermissionEntry> entries = new ArrayList<>();
        for (Object entry : iterable) {
            entries.add(fileSystemEntry(entry));
        }
        return List.copyOf(entries);
    }

    private FileSystemPermissionEntry fileSystemEntry(Object value) {
        if (value instanceof FileSystemPermissionEntry entry) {
            validateSupportedFileSystemEntry(entry);
            return entry;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("permissions.fileSystem.entries[] 必须为对象。");
        }
        return new FileSystemPermissionEntry(
            fileSystemPath(map.get("path")),
            enumValue(FileSystemAccessMode.class, map.get("access"), "permissions.fileSystem.entries[].access")
        );
    }

    private void validateSupportedFileSystemEntries(List<FileSystemPermissionEntry> entries) {
        for (FileSystemPermissionEntry entry : entries) {
            validateSupportedFileSystemEntry(entry);
        }
    }

    private AdditionalPermissionProfile validateSupportedAdditionalPermissions(AdditionalPermissionProfile permissions) {
        permissions.fileSystem().ifPresent(this::validateSupportedFileSystemPolicy);
        return permissions;
    }

    private void validateSupportedFileSystemPolicy(FileSystemPermissionPolicy policy) {
        if (policy.kind() != FileSystemPolicyKind.RESTRICTED) {
            throw new IllegalArgumentException("permissions.fileSystem 第一版只支持 RESTRICTED。");
        }
        validateSupportedFileSystemEntries(policy.entries());
    }

    private void validateSupportedFileSystemEntry(FileSystemPermissionEntry entry) {
        if (entry.path().kind() != FileSystemPath.Kind.EXACT_PATH) {
            throw new IllegalArgumentException("permissions.fileSystem.entries[].path 第一版只支持 EXACT_PATH。");
        }
        if (entry.access() == FileSystemAccessMode.DENY) {
            throw new IllegalArgumentException("permissions.fileSystem.entries[].access 第一版不支持 DENY。");
        }
    }

    private FileSystemPath fileSystemPath(Object value) {
        if (value instanceof FileSystemPath path) {
            return path;
        }
        if (value instanceof String path) {
            return path.startsWith(":")
                ? FileSystemPath.special(FileSystemSpecialPath.fromJson(path))
                : FileSystemPath.exactPath(path);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("permissions.fileSystem.entries[].path 必须为对象或字符串。");
        }
        FileSystemPath.Kind kind = enumValue(FileSystemPath.Kind.class, map.get("kind"), "permissions.fileSystem.entries[].path.kind");
        Object rawValue = map.get("value");
        if (rawValue == null || rawValue.toString().isBlank()) {
            throw new IllegalArgumentException("permissions.fileSystem.entries[].path.value 不能为空。");
        }
        return new FileSystemPath(kind, rawValue.toString());
    }

    private Optional<NetworkPermissionPolicy> networkPolicy(Object value) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof NetworkPermissionPolicy policy) {
            return Optional.of(policy);
        }
        if (value instanceof String mode) {
            return Optional.of(new NetworkPermissionPolicy(enumValue(NetworkPolicyMode.class, mode, "permissions.network")));
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("permissions.network 必须为对象。");
        }
        return Optional.of(new NetworkPermissionPolicy(enumValue(NetworkPolicyMode.class, map.get("mode"), "permissions.network.mode")));
    }

    private PermissionGrantScope scope(Map<String, Object> input) {
        Object value = input == null ? null : input.get(INPUT_SCOPE);
        if (value == null || value.toString().isBlank()) {
            return PermissionGrantScope.TURN;
        }
        return enumValue(PermissionGrantScope.class, value, "scope");
    }

    private <E extends Enum<E>> E enumValue(Class<E> enumType, Object value, String fieldName) {
        if (enumType.isInstance(value)) {
            return enumType.cast(value);
        }
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空。");
        }
        try {
            return Enum.valueOf(enumType, value.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(fieldName + " 不合法: " + value);
        }
    }

    private boolean isEmpty(AdditionalPermissionProfile permissions) {
        return permissions.fileSystem().isEmpty() && permissions.network().isEmpty();
    }

    private boolean booleanInput(Map<String, Object> input, String key) {
        Object value = input == null ? null : input.get(key);
        if (value instanceof Boolean flag) {
            return flag;
        }
        return value instanceof String flag && Boolean.parseBoolean(flag);
    }

    private String reason(Map<String, Object> input) {
        Object reason = input == null ? null : input.get(INPUT_REASON);
        return reason == null || reason.toString().isBlank() ? "请求额外权限。" : reason.toString();
    }

    private String json(Object value) {
        try {
            return JSON_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化 request_permissions 响应失败。", exception);
        }
    }

    private Object firstPresent(Map<?, ?> map, String firstKey, String secondKey) {
        Object first = map.get(firstKey);
        return first == null ? map.get(secondKey) : first;
    }

    private Map<?, ?> unwrapAdditionalPermissions(Map<?, ?> permissions) {
        Object value = permissions.get("additionalPermissions");
        return value instanceof Map<?, ?> additionalPermissions ? additionalPermissions : permissions;
    }

    private String toolUseId(ToolUseContext context) {
        return ToolMessages.toolUseId(context);
    }

    private ValidationResult valid() {
        return new ValidationResult(true, List.of());
    }

    private ValidationResult invalid(String message) {
        return new ValidationResult(false, List.of(message));
    }
}
