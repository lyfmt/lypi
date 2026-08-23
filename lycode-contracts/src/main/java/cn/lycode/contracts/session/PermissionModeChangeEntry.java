package cn.lycode.contracts.session;

import cn.lycode.contracts.security.PermissionMode;
import java.time.Instant;

public record PermissionModeChangeEntry(
    String id,
    String parentId,
    PermissionMode permissionMode,
    String reason,
    Instant timestamp
) implements SessionEntry {}

