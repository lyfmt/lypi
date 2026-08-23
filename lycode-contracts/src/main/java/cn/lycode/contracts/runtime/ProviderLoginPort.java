package cn.lycode.contracts.runtime;

/**
 * 注册可在当前进程立即使用的模型 provider。
 *
 * <p>调用方不得记录 auth key，也不得将其写入 session 或事件内容。</p>
 */
@FunctionalInterface
public interface ProviderLoginPort {
    ProviderLoginResult register(String channelName, String baseUrl, String authKey);

    static ProviderLoginPort unavailable() {
        return (channelName, baseUrl, authKey) -> {
            throw new IllegalStateException("provider login is unavailable");
        };
    }
}
