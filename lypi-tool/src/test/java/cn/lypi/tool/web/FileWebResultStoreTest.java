package cn.lypi.tool.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileWebResultStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void savesJsonlUnderRuntimeDirectoryAndReadsBySession() throws Exception {
        FileWebResultStore store = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));

        WebStoredResult saved = store.save(result("session-a", "java"));

        assertEquals("web_20260623_000001", saved.responseId());
        assertTrue(Files.exists(tempDir.resolve(".ly-pi/web-results.jsonl")));

        Optional<WebStoredResult> found = store.findByResponseId("session-a", saved.responseId());
        Optional<WebStoredResult> otherSession = store.findByResponseId("session-b", saved.responseId());

        assertTrue(found.isPresent());
        assertEquals("message-1", found.orElseThrow().messageId());
        assertEquals("https://example.com/a", found.orElseThrow().items().getFirst().url());
        assertTrue(otherSession.isEmpty());
    }

    @Test
    void readsLatestMatchingQueryWithinSession() {
        FileWebResultStore store = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));

        WebStoredResult first = store.save(result("session-a", "java"));
        WebStoredResult second = store.save(new WebStoredResult(
            "session-a",
            "message-2",
            "",
            "web_search",
            Optional.of("java"),
            Optional.empty(),
            List.of(item("https://example.com/new", "New", "new content", false)),
            Instant.parse("2026-06-23T00:00:01Z")
        ));
        store.save(result("session-b", "java"));

        Optional<WebStoredResult> latest = store.findLatestByQuery("session-a", " java ");

        assertTrue(latest.isPresent());
        assertEquals(second.responseId(), latest.orElseThrow().responseId());
        assertFalse(latest.orElseThrow().responseId().equals(first.responseId()));
    }

    @Test
    void skipsMalformedJsonlRowsWhenReading() throws Exception {
        Path cache = tempDir.resolve(".ly-pi/web-results.jsonl");
        Files.createDirectories(cache.getParent());
        Files.writeString(cache, "{not-json}\n");
        FileWebResultStore store = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));
        WebStoredResult saved = store.save(result("session-a", "java"));

        Optional<WebStoredResult> found = store.findByResponseId("session-a", saved.responseId());

        assertTrue(found.isPresent());
        assertEquals(saved.responseId(), found.orElseThrow().responseId());
    }

    @Test
    void truncatesLargeItemContentBeforePersisting() {
        FileWebResultStore store = new FileWebResultStore(
            tempDir,
            () -> Instant.parse("2026-06-23T00:00:00Z"),
            12
        );

        WebStoredResult saved = store.save(new WebStoredResult(
            "session-a",
            "message-1",
            "",
            "web_fetch",
            Optional.empty(),
            Optional.of("https://example.com/a"),
            List.of(item("https://example.com/a", "Title", "123456789012345", false)),
            Instant.parse("2026-06-23T00:00:00Z")
        ));

        WebStoredItem item = saved.items().getFirst();

        assertEquals("123456789012", item.content());
        assertTrue(item.truncated());
    }

    @Test
    void emptyCacheReadsUseStoreLock() {
        FileWebResultStore store = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));

        Optional<WebStoredResult> missing = store.findByResponseId("session-a", "web_20260623_000001");

        assertTrue(missing.isEmpty());
        assertTrue(Files.exists(tempDir.resolve(".ly-pi/web-results.jsonl.lock")));
    }

    @Test
    void assignsUniqueResponseIdsAcrossConcurrentStoreInstances() throws Exception {
        FileWebResultStore firstStore = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));
        FileWebResultStore secondStore = new FileWebResultStore(tempDir, () -> Instant.parse("2026-06-23T00:00:00Z"));
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> firstSave = () -> saveAfterStart(firstStore, "session-a", "java", start);
        Callable<String> secondSave = () -> saveAfterStart(secondStore, "session-b", "kotlin", start);

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(firstSave);
            Future<String> second = executor.submit(secondSave);
            start.countDown();

            List<String> ids = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));

            assertEquals(2, Set.copyOf(ids).size());
            assertEquals(2, Files.readAllLines(tempDir.resolve(".ly-pi/web-results.jsonl")).size());
        }
    }

    private WebStoredResult result(String sessionId, String query) {
        return new WebStoredResult(
            sessionId,
            "message-1",
            "",
            "web_search",
            Optional.of(query),
            Optional.empty(),
            List.of(item("https://example.com/a", "Example", "content", false)),
            Instant.parse("2026-06-23T00:00:00Z")
        );
    }

    private String saveAfterStart(
        FileWebResultStore store,
        String sessionId,
        String query,
        CountDownLatch start
    ) throws Exception {
        assertTrue(start.await(5, TimeUnit.SECONDS));
        return store.save(result(sessionId, query)).responseId();
    }

    private WebStoredItem item(String url, String title, String content, boolean truncated) {
        return new WebStoredItem(
            url,
            Optional.of(title),
            Optional.of("snippet"),
            content,
            Optional.of("markdown"),
            truncated,
            Optional.empty()
        );
    }
}
