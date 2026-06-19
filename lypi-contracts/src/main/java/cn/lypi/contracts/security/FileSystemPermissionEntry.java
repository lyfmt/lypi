package cn.lypi.contracts.security;

import java.util.Objects;

public record FileSystemPermissionEntry(
    FileSystemPath path,
    FileSystemAccessMode access
) {
    public FileSystemPermissionEntry {
        path = Objects.requireNonNull(path, "path");
        access = Objects.requireNonNull(access, "access");
    }
}
