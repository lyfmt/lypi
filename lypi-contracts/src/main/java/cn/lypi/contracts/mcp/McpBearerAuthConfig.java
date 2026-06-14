package cn.lypi.contracts.mcp;

/**
 * 描述 Bearer token 鉴权配置。
 *
 * tokenEnv 表示运行时从环境变量读取 token，token 表示配置中的静态 token。
 */
public record McpBearerAuthConfig(
    String tokenEnv,
    String token
) implements McpAuthConfig {
}
