package cn.lypi.tool;

import cn.lypi.contracts.security.AdditionalPermissionProfile;
import cn.lypi.contracts.security.FileSystemAccessMode;
import cn.lypi.contracts.security.FileSystemPath;
import cn.lypi.contracts.security.FileSystemPermissionEntry;
import cn.lypi.contracts.security.FileSystemPermissionPolicy;
import cn.lypi.contracts.security.FileSystemPolicyKind;
import cn.lypi.contracts.security.FileSystemSpecialPath;
import cn.lypi.contracts.security.NetworkPermissionPolicy;
import cn.lypi.contracts.security.NetworkPolicyMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 解析工具输入中的单次额外权限请求。
 */
final class AdditionalPermissionsInputParser {
    private AdditionalPermissionsInputParser() {
    }

    static AdditionalPermissionProfile parse(Object value, String fieldName) {
        if (value instanceof AdditionalPermissionProfile profile) {
            return validateExecutable(profile);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须为对象。");
        }
        return validateExecutable(new AdditionalPermissionProfile(
            fileSystemPolicy(firstPresent(map, "fileSystem", "filesystem"), fieldName + ".fileSystem"),
            networkPolicy(map.get("network"), fieldName + ".network")
        ));
    }

    static boolean isEmpty(AdditionalPermissionProfile permissions) {
        return permissions == null || (permissions.fileSystem().isEmpty() && permissions.network().isEmpty());
    }

    private static AdditionalPermissionProfile validateExecutable(AdditionalPermissionProfile permissions) {
        AdditionalPermissionProfile safePermissions = permissions == null
            ? AdditionalPermissionProfile.empty()
            : permissions;
        safePermissions.fileSystem().ifPresent(AdditionalPermissionsInputParser::validateFileSystemPolicy);
        return safePermissions;
    }

    private static void validateFileSystemPolicy(FileSystemPermissionPolicy policy) {
        if (policy.kind() != FileSystemPolicyKind.RESTRICTED) {
            throw new IllegalArgumentException("additionalPermissions.fileSystem 只支持 RESTRICTED。");
        }
        for (FileSystemPermissionEntry entry : policy.entries()) {
            if (entry.path().kind() != FileSystemPath.Kind.EXACT_PATH) {
                throw new IllegalArgumentException("additionalPermissions.fileSystem.entries[].path 只支持 EXACT_PATH。");
            }
            if (entry.access() == FileSystemAccessMode.DENY) {
                throw new IllegalArgumentException("additionalPermissions.fileSystem.entries[].access 不支持 DENY。");
            }
        }
    }

    private static Optional<FileSystemPermissionPolicy> fileSystemPolicy(Object value, String fieldName) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof FileSystemPermissionPolicy policy) {
            return Optional.of(policy);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须为对象。");
        }
        FileSystemPolicyKind kind = map.containsKey("kind")
            ? enumValue(FileSystemPolicyKind.class, map.get("kind"), fieldName + ".kind")
            : FileSystemPolicyKind.RESTRICTED;
        Object rawEntries = map.get("entries");
        if (!(rawEntries instanceof Iterable<?> iterable)) {
            throw new IllegalArgumentException(fieldName + ".entries 必须为数组。");
        }
        List<FileSystemPermissionEntry> entries = new ArrayList<>();
        for (Object entry : iterable) {
            entries.add(fileSystemEntry(entry, fieldName + ".entries[]"));
        }
        return Optional.of(new FileSystemPermissionPolicy(kind, entries));
    }

    private static FileSystemPermissionEntry fileSystemEntry(Object value, String fieldName) {
        if (value instanceof FileSystemPermissionEntry entry) {
            return entry;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须为对象。");
        }
        return new FileSystemPermissionEntry(
            fileSystemPath(map.get("path"), fieldName + ".path"),
            enumValue(FileSystemAccessMode.class, map.get("access"), fieldName + ".access")
        );
    }

    private static FileSystemPath fileSystemPath(Object value, String fieldName) {
        if (value instanceof FileSystemPath path) {
            return path;
        }
        if (value instanceof String path) {
            return path.startsWith(":")
                ? FileSystemPath.special(FileSystemSpecialPath.fromJson(path))
                : FileSystemPath.exactPath(path);
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须为对象或字符串。");
        }
        return new FileSystemPath(
            enumValue(FileSystemPath.Kind.class, map.get("kind"), fieldName + ".kind"),
            valueString(map.get("value"), fieldName + ".value")
        );
    }

    private static Optional<NetworkPermissionPolicy> networkPolicy(Object value, String fieldName) {
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof NetworkPermissionPolicy policy) {
            return Optional.of(policy);
        }
        if (value instanceof String mode) {
            return Optional.of(new NetworkPermissionPolicy(enumValue(NetworkPolicyMode.class, mode, fieldName)));
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(fieldName + " 必须为对象。");
        }
        return Optional.of(new NetworkPermissionPolicy(enumValue(NetworkPolicyMode.class, map.get("mode"), fieldName + ".mode")));
    }

    private static Object firstPresent(Map<?, ?> map, String first, String second) {
        Object value = map.get(first);
        return value == null ? map.get(second) : value;
    }

    private static String valueString(Object value, String fieldName) {
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException(fieldName + " 不能为空。");
        }
        return value.toString();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> enumType, Object value, String fieldName) {
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
}
