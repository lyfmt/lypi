package cn.lypi.contracts.security;

import java.util.List;
import java.util.Objects;

public final class PermissionProfiles {
    private static final ManagedPermissionProfile READ_ONLY = new ManagedPermissionProfile(
        FileSystemPermissionPolicy.restricted(List.of(
            new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.ROOT),
                FileSystemAccessMode.READ
            )
        )),
        NetworkPermissionPolicy.restricted()
    );

    private static final ManagedPermissionProfile WORKSPACE = new ManagedPermissionProfile(
        FileSystemPermissionPolicy.restricted(List.of(
            new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.ROOT),
                FileSystemAccessMode.READ
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.PROJECT_ROOTS),
                FileSystemAccessMode.WRITE
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.TMPDIR),
                FileSystemAccessMode.WRITE
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.special(FileSystemSpecialPath.SLASH_TMP),
                FileSystemAccessMode.WRITE
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.exactPath(".git"),
                FileSystemAccessMode.READ
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.exactPath(".agents"),
                FileSystemAccessMode.READ
            ),
            new FileSystemPermissionEntry(
                FileSystemPath.exactPath(".codex"),
                FileSystemAccessMode.READ
            )
        )),
        NetworkPermissionPolicy.restricted()
    );

    private static final DisabledPermissionProfile DANGER_FULL_ACCESS = new DisabledPermissionProfile();

    private PermissionProfiles() {
    }

    public static ManagedPermissionProfile readOnly() {
        return READ_ONLY;
    }

    public static ManagedPermissionProfile workspace() {
        return WORKSPACE;
    }

    public static DisabledPermissionProfile dangerFullAccess() {
        return DANGER_FULL_ACCESS;
    }

    public static ExternalPermissionProfile external(NetworkPermissionPolicy network) {
        return new ExternalPermissionProfile(Objects.requireNonNull(network, "network"));
    }
}
