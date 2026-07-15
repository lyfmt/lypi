package cn.lypi.ai.provider;

import java.util.Locale;

public final class ProviderFallbackDecider {
    /**
     * 判断一次 provider 失败是否允许进入下一种请求方言。
     *
     * 已经向上游发出用户可见内容或工具调用后不再静默回退，避免重复执行同一用户请求。
     * Provider message ID 和 control notice 不属于可见输出。
     */
    public boolean shouldFallback(RuntimeException error, boolean visibleOutputStarted) {
        if (visibleOutputStarted) {
            return false;
        }
        String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("aborted")
            || message.contains("401")
            || message.contains("403")
            || message.contains("429")) {
            return false;
        }
        return message.contains("unsupported")
            || message.contains("404")
            || message.contains("405")
            || message.contains("handshake failed")
            || message.contains("previous_response_id")
            || message.contains("without assistantdone");
    }
}
