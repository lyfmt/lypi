package cn.lypi.contracts.security;

import java.util.Optional;

public record AdditionalPermissionProfile(
    Optional<FileSystemPermissionPolicy> fileSystem,
    Optional<NetworkPermissionPolicy> network
) {
    public AdditionalPermissionProfile {
        fileSystem = fileSystem == null ? Optional.empty() : fileSystem;
        network = network == null ? Optional.empty() : network;
    }

    public static AdditionalPermissionProfile empty() {
        return new AdditionalPermissionProfile(Optional.empty(), Optional.empty());
    }
}
