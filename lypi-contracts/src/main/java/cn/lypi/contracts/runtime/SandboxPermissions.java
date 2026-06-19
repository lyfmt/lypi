package cn.lypi.contracts.runtime;

import java.util.Locale;

public enum SandboxPermissions {
    USE_DEFAULT("useDefault"),
    REQUIRE_ESCALATED("requireEscalated"),
    WITH_ADDITIONAL_PERMISSIONS("withAdditionalPermissions");

    private final String toolValue;

    SandboxPermissions(String toolValue) {
        this.toolValue = toolValue;
    }

    /**
     * 返回工具输入使用的稳定字符串值。
     */
    public String toolValue() {
        return toolValue;
    }

    /**
     * 从工具输入字符串解析沙箱权限请求。
     */
    public static SandboxPermissions fromToolValue(String value) {
        if (value == null || value.isBlank()) {
            return USE_DEFAULT;
        }
        String normalized = value.trim();
        for (SandboxPermissions permission : values()) {
            if (permission.toolValue.equals(normalized)) {
                return permission;
            }
        }
        return SandboxPermissions.valueOf(normalized.toUpperCase(Locale.ROOT));
    }
}
