package cn.lypi.contracts.security;

import java.util.Objects;

public record ManagedPermissionProfile(
    FileSystemPermissionPolicy fileSystem,
    NetworkPermissionPolicy network
) implements PermissionProfile {
    public ManagedPermissionProfile {
        fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        network = Objects.requireNonNull(network, "network");
    }

    @Override
    public Kind kind() {
        return Kind.MANAGED;
    }
}
