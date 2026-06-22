package cn.lypi.tool.web;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 检查 Web URL 是否适合发送给联网抓取工具。
 */
public final class WebUrlPolicy {
    private WebUrlPolicy() {
    }

    /**
     * 校验并解析 URL。
     */
    public static CheckedUrl check(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("url scheme 只支持 http 或 https。");
        }
        if (uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException("url 不能包含 credential。");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("url host 不能为空。");
        }
        String normalizedHost = normalizeHost(host);
        rejectUnsafeHost(normalizedHost);
        return new CheckedUrl(uri, normalizedHost);
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url 不能为空。");
        }
        try {
            return new URI(url.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("url 格式无效: " + exception.getMessage(), exception);
        }
    }

    private static String normalizeHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static void rejectUnsafeHost(String host) {
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("127.0.0.1") || host.equals("::1")) {
            throw new IllegalArgumentException("url host 不能是 local 地址。");
        }
        if (isUnspecified(host)) {
            throw new IllegalArgumentException("url host 不能是 unspecified 地址。");
        }
        if (isPrivateIpv4(host)) {
            throw new IllegalArgumentException("url host 不能是 private 地址。");
        }
        if (isPrivateIpv6(host)) {
            throw new IllegalArgumentException("url host 不能是 private 地址。");
        }
        if (isLinkLocal(host)) {
            throw new IllegalArgumentException("url host 不能是 link-local 地址。");
        }
    }

    private static boolean isPrivateIpv4(String host) {
        int[] parts = ipv4Parts(host);
        if (parts.length != 4) {
            return false;
        }
        return parts[0] == 10
            || parts[0] == 127
            || (parts[0] == 172 && parts[1] >= 16 && parts[1] <= 31)
            || (parts[0] == 192 && parts[1] == 168);
    }

    private static boolean isPrivateIpv6(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("fc") || normalized.startsWith("fd")) {
            return true;
        }
        if (normalized.startsWith("::ffff:")) {
            return isPrivateIpv4(normalized.substring("::ffff:".length()));
        }
        return false;
    }

    private static boolean isLinkLocal(String host) {
        int[] parts = ipv4Parts(host);
        if (parts.length == 4 && parts[0] == 169 && parts[1] == 254) {
            return true;
        }
        return host.startsWith("fe80:");
    }

    private static boolean isUnspecified(String host) {
        return host.equals("0.0.0.0") || host.equals("::");
    }

    private static int[] ipv4Parts(String host) {
        String[] tokens = host.split("\\.", -1);
        if (tokens.length != 4) {
            return new int[0];
        }
        int[] parts = new int[4];
        for (int index = 0; index < tokens.length; index++) {
            try {
                parts[index] = Integer.parseInt(tokens[index]);
            } catch (NumberFormatException exception) {
                return new int[0];
            }
            if (parts[index] < 0 || parts[index] > 255) {
                return new int[0];
            }
        }
        return parts;
    }

    /**
     * 返回校验后的 URL 和规范化 host。
     */
    public record CheckedUrl(URI uri, String host) {
    }
}
