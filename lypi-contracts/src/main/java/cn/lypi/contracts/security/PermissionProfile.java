package cn.lypi.contracts.security;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * 描述可执行的权限 profile。
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ManagedPermissionProfile.class, name = "managed"),
    @JsonSubTypes.Type(value = DisabledPermissionProfile.class, name = "disabled"),
    @JsonSubTypes.Type(value = ExternalPermissionProfile.class, name = "external")
})
public sealed interface PermissionProfile permits
    ManagedPermissionProfile,
    DisabledPermissionProfile,
    ExternalPermissionProfile {
    /**
     * 返回 profile 类型。
     */
    Kind kind();

    /**
     * 返回文件系统权限策略。
     */
    FileSystemPermissionPolicy fileSystem();

    /**
     * 返回网络权限策略。
     */
    NetworkPermissionPolicy network();

    enum Kind {
        MANAGED,
        DISABLED,
        EXTERNAL
    }
}
