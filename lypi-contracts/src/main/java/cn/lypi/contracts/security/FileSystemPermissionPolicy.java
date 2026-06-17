package cn.lypi.contracts.security;

import java.util.List;
import java.util.Objects;

public record FileSystemPermissionPolicy(
    FileSystemPolicyKind kind,
    List<FileSystemPermissionEntry> entries
) {
    public FileSystemPermissionPolicy {
        kind = Objects.requireNonNull(kind, "kind");
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        entries = List.copyOf(entries);
        if (kind != FileSystemPolicyKind.RESTRICTED && !entries.isEmpty()) {
            throw new IllegalArgumentException(kind + " filesystem policy must not include entries");
        }
    }

    public static FileSystemPermissionPolicy restricted(List<FileSystemPermissionEntry> entries) {
        return new FileSystemPermissionPolicy(FileSystemPolicyKind.RESTRICTED, entries);
    }

    public static FileSystemPermissionPolicy unrestricted() {
        return new FileSystemPermissionPolicy(FileSystemPolicyKind.UNRESTRICTED, List.of());
    }

    public static FileSystemPermissionPolicy externalSandbox() {
        return new FileSystemPermissionPolicy(FileSystemPolicyKind.EXTERNAL_SANDBOX, List.of());
    }
}
