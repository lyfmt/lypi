package cn.lypi.contracts.agent;

import cn.lypi.contracts.security.PermissionResponse;
import java.util.Objects;

/**
 * 请求恢复一次等待权限的 turn。
 *
 * NOTE: transport 只提交用户决策，agent-core 负责查找 pending 并继续编排。
 */
public record PermissionResumeRequest(
    PermissionResponse response
) {
    public PermissionResumeRequest {
        response = Objects.requireNonNull(response, "response must not be null");
    }
}
