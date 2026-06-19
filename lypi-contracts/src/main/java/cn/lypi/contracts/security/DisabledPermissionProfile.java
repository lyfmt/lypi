package cn.lypi.contracts.security;

public record DisabledPermissionProfile() implements PermissionProfile {
    private static final FileSystemPermissionPolicy FILE_SYSTEM = FileSystemPermissionPolicy.unrestricted();
    private static final NetworkPermissionPolicy NETWORK = NetworkPermissionPolicy.enabled();

    @Override
    public Kind kind() {
        return Kind.DISABLED;
    }

    @Override
    public FileSystemPermissionPolicy fileSystem() {
        return FILE_SYSTEM;
    }

    @Override
    public NetworkPermissionPolicy network() {
        return NETWORK;
    }
}
