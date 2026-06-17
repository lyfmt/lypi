package cn.lypi.contracts.security;

import java.util.Objects;

public record ExternalPermissionProfile(
    NetworkPermissionPolicy network
) implements PermissionProfile {
    private static final FileSystemPermissionPolicy FILE_SYSTEM = FileSystemPermissionPolicy.externalSandbox();

    public ExternalPermissionProfile {
        network = Objects.requireNonNull(network, "network");
    }

    @Override
    public Kind kind() {
        return Kind.EXTERNAL;
    }

    @Override
    public FileSystemPermissionPolicy fileSystem() {
        return FILE_SYSTEM;
    }
}
