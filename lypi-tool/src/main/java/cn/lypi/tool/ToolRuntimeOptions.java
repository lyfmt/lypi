package cn.lypi.tool;

import java.nio.file.Path;
import java.util.Map;

/**
 * 工具运行时执行选项。
 *
 * NOTE: 真实 sessionId、cwd 和调用级 metadata 应由上层 runtime 注入。
 */
public final class ToolRuntimeOptions {
    private static final String DEFAULT_SESSION_ID = "session_unknown";
    private static final int DEFAULT_MAX_CONCURRENCY = 10;

    private final String sessionId;
    private final Path cwd;
    private final Map<String, Object> metadata;
    private final int maxConcurrency;

    private ToolRuntimeOptions(Builder builder) {
        this.sessionId = normalizeSessionId(builder.sessionId);
        this.cwd = normalizeCwd(builder.cwd);
        this.metadata = Map.copyOf(builder.metadata);
        this.maxConcurrency = Math.max(1, builder.maxConcurrency);
    }

    /**
     * 返回默认工具运行时选项。
     */
    public static ToolRuntimeOptions defaults() {
        return builder().build();
    }

    /**
     * 创建工具运行时选项构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    public String sessionId() {
        return sessionId;
    }

    public Path cwd() {
        return cwd;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public int maxConcurrency() {
        return maxConcurrency;
    }

    private String normalizeSessionId(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_SESSION_ID;
        }
        return value;
    }

    private Path normalizeCwd(Path value) {
        if (value == null) {
            return Path.of(".").toAbsolutePath().normalize();
        }
        return value.toAbsolutePath().normalize();
    }

    public static final class Builder {
        private String sessionId = DEFAULT_SESSION_ID;
        private Path cwd = Path.of(".").toAbsolutePath().normalize();
        private Map<String, Object> metadata = Map.of();
        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

        private Builder() {
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder cwd(Path cwd) {
            this.cwd = cwd;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public ToolRuntimeOptions build() {
            return new ToolRuntimeOptions(this);
        }
    }
}
