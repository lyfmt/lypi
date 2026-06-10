package cn.lypi.contracts.runtime;

/**
 * 表示一次 AI 流式调用的轻量运行选项。
 *
 * NOTE: 该契约只携带调用身份，不表达具体 provider 的协议字段。
 */
public record AiStreamOptions(String sessionId) {
    public AiStreamOptions {
        sessionId = sessionId == null ? "" : sessionId;
    }

    /**
     * 返回空 AI 流式调用选项。
     */
    public static AiStreamOptions empty() {
        return new AiStreamOptions("");
    }
}
