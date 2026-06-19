package cn.lypi.contracts.security;

import java.util.Optional;

public record ActivePermissionProfile(
    String id,
    Optional<String> extendsProfile
) {
    public ActivePermissionProfile {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        extendsProfile = extendsProfile == null ? Optional.empty() : extendsProfile;
    }

    public ActivePermissionProfile(String id) {
        this(id, Optional.empty());
    }
}
