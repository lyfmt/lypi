package cn.lypi.security;

import cn.lypi.contracts.runtime.SecurityRuntimePort;

/**
 * 决定工具调用是否允许执行。
 *
 * NOTE: 策略引擎只产出权限决策，不执行工具，也不实现 sandbox。
 */
public interface PolicyEngine extends SecurityRuntimePort {
}
