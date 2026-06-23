package cn.lypi.tool.web;

/**
 * 表示 Web provider 调用失败。
 */
public final class WebProviderException extends RuntimeException {
    public WebProviderException(String message) {
        super(message);
    }

    public WebProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
