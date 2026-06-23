package cn.lypi.tool.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 使用 `.ly-pi/web-results.jsonl` 持久化 Web 工具结果。
 */
public final class FileWebResultStore implements WebResultStore {
    private static final int DEFAULT_MAX_ITEM_CONTENT_CHARS = 200_000;
    private static final DateTimeFormatter RESPONSE_ID_DATE = DateTimeFormatter
        .ofPattern("yyyyMMdd")
        .withZone(ZoneOffset.UTC);
    private static final Map<Path, Object> STORE_LOCKS = new ConcurrentHashMap<>();

    private final Path storeFile;
    private final Path lockFile;
    private final Object storeLock;
    private final Supplier<Instant> clock;
    private final int maxItemContentChars;
    private final ObjectMapper jsonMapper;

    public FileWebResultStore(Path runtimeCwd) {
        this(runtimeCwd, Instant::now);
    }

    FileWebResultStore(Path runtimeCwd, Supplier<Instant> clock) {
        this(runtimeCwd, clock, DEFAULT_MAX_ITEM_CONTENT_CHARS);
    }

    FileWebResultStore(Path runtimeCwd, Supplier<Instant> clock, int maxItemContentChars) {
        Path root = runtimeCwd == null ? Path.of(".") : runtimeCwd;
        this.storeFile = root.resolve(".ly-pi").resolve("web-results.jsonl").toAbsolutePath().normalize();
        this.lockFile = storeFile.resolveSibling(storeFile.getFileName() + ".lock");
        this.storeLock = STORE_LOCKS.computeIfAbsent(storeFile, ignored -> new Object());
        this.clock = clock == null ? Instant::now : clock;
        this.maxItemContentChars = Math.max(1, maxItemContentChars);
        this.jsonMapper = new ObjectMapper().registerModule(new Jdk8Module());
    }

    @Override
    public WebStoredResult save(WebStoredResult result) {
        try {
            Files.createDirectories(storeFile.getParent());
            return withStoreLock(() -> {
                WebStoredResult normalized = normalize(result);
                Files.writeString(
                    storeFile,
                    jsonMapper.writeValueAsString(StoredWebResult.from(normalized)) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );
                return normalized;
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Web 结果缓存写入失败: " + exception.getMessage(), exception);
        }
    }

    @Override
    public Optional<WebStoredResult> findByResponseId(String sessionId, String responseId) {
        String normalizedSessionId = normalizeText(sessionId);
        String normalizedResponseId = normalizeText(responseId);
        if (normalizedResponseId.isBlank()) {
            return Optional.empty();
        }
        return readWithLock(() -> readResults().stream()
            .filter(result -> result.sessionId().equals(normalizedSessionId))
            .filter(result -> result.responseId().equals(normalizedResponseId))
            .reduce((first, second) -> second));
    }

    @Override
    public Optional<WebStoredResult> findLatestByQuery(String sessionId, String query) {
        String normalizedSessionId = normalizeText(sessionId);
        String normalizedQuery = normalizeQuery(query);
        if (normalizedQuery.isBlank()) {
            return Optional.empty();
        }
        return readWithLock(() -> readResults().stream()
            .filter(result -> result.sessionId().equals(normalizedSessionId))
            .filter(result -> result.query().map(FileWebResultStore::normalizeQuery).orElse("").equals(normalizedQuery))
            .reduce((first, second) -> second));
    }

    private <T> T readWithLock(StoreOperation<T> operation) {
        try {
            Files.createDirectories(storeFile.getParent());
            return withStoreLock(operation);
        } catch (IOException exception) {
            throw new IllegalStateException("Web 结果缓存读取失败: " + exception.getMessage(), exception);
        }
    }

    private <T> T withStoreLock(StoreOperation<T> operation) throws IOException {
        synchronized (storeLock) {
            try (
                FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                FileLock ignored = channel.lock()
            ) {
                return operation.run();
            }
        }
    }

    private WebStoredResult normalize(WebStoredResult result) {
        if (result == null) {
            throw new IllegalArgumentException("web result must not be null");
        }
        String responseId = result.responseId().isBlank() ? nextResponseId() : result.responseId();
        return new WebStoredResult(
            result.sessionId(),
            result.messageId(),
            responseId,
            result.sourceTool(),
            result.query(),
            result.url(),
            truncateItems(result.items()),
            result.createdAt()
        );
    }

    private String nextResponseId() {
        String date = RESPONSE_ID_DATE.format(clock.get());
        int next = readResults().stream()
            .map(WebStoredResult::responseId)
            .filter(id -> id.startsWith("web_" + date + "_"))
            .mapToInt(this::responseSequence)
            .max()
            .orElse(0) + 1;
        return "web_" + date + "_" + "%06d".formatted(next);
    }

    private int responseSequence(String responseId) {
        int index = responseId.lastIndexOf('_');
        if (index < 0 || index == responseId.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(responseId.substring(index + 1));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private List<WebStoredItem> truncateItems(List<WebStoredItem> items) {
        List<WebStoredItem> normalized = new ArrayList<>();
        for (WebStoredItem item : items == null ? List.<WebStoredItem>of() : items) {
            String content = item.content();
            boolean truncated = item.truncated();
            if (content.length() > maxItemContentChars) {
                content = content.substring(0, maxItemContentChars);
                truncated = true;
            }
            normalized.add(new WebStoredItem(
                item.url(),
                item.title(),
                item.snippet(),
                content,
                item.format(),
                truncated,
                item.source()
            ));
        }
        return List.copyOf(normalized);
    }

    private List<WebStoredResult> readResults() {
        if (!Files.isRegularFile(storeFile)) {
            return List.of();
        }
        try {
            List<WebStoredResult> results = new ArrayList<>();
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    StoredWebResult stored = jsonMapper.readValue(line, StoredWebResult.class);
                    results.add(stored.toWebStoredResult());
                } catch (IOException | RuntimeException exception) {
                    // NOTE: 单行损坏不能影响后续缓存记录读取。
                }
            }
            return List.copyOf(results);
        } catch (IOException exception) {
            throw new IllegalStateException("Web 结果缓存读取失败: " + exception.getMessage(), exception);
        }
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.trim();
    }

    private static String normalizeQuery(String value) {
        return normalizeText(value).toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    private interface StoreOperation<T> {
        T run() throws IOException;
    }

    private record StoredWebResult(
        String sessionId,
        String messageId,
        String responseId,
        String sourceTool,
        Optional<String> query,
        Optional<String> url,
        List<WebStoredItem> items,
        String createdAt
    ) {
        private StoredWebResult {
            query = query == null ? Optional.empty() : query;
            url = url == null ? Optional.empty() : url;
            items = items == null ? List.of() : List.copyOf(items);
        }

        static StoredWebResult from(WebStoredResult result) {
            return new StoredWebResult(
                result.sessionId(),
                result.messageId(),
                result.responseId(),
                result.sourceTool(),
                result.query(),
                result.url(),
                result.items(),
                result.createdAt().toString()
            );
        }

        WebStoredResult toWebStoredResult() {
            return new WebStoredResult(
                sessionId,
                messageId,
                responseId,
                sourceTool,
                query,
                url,
                items,
                parseInstant(createdAt)
            );
        }

        private Instant parseInstant(String value) {
            try {
                return value == null || value.isBlank() ? Instant.EPOCH : Instant.parse(value);
            } catch (RuntimeException exception) {
                return Instant.EPOCH;
            }
        }
    }
}
