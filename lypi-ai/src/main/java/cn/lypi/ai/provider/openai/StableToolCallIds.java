package cn.lypi.ai.provider.openai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class StableToolCallIds {
    private StableToolCallIds() {
    }

    /**
     * 生成 provider 缺失 tool call id 时使用的稳定 id。
     *
     * 输入相同时返回相同 id，便于增量事件聚合。
     */
    public static String from(String providerEventKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(providerEventKey.getBytes(StandardCharsets.UTF_8));
            return "call_" + HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
