package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.lypi.contracts.session.CustomMessageEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionJsonlLargeFileTest {
    private static final Instant BASE = Instant.parse("2026-06-23T00:00:00Z");

    @TempDir
    Path tempDir;

    @Test
    void readParsesLargeJsonlLineByLineAndReportsEntryLineNumber() throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_large"));
        for (int i = 0; i < 5_000; i++) {
            store.append("ses_large", new CustomMessageEntry(
                "entry_" + i,
                i == 0 ? null : "entry_" + (i - 1),
                "message " + i,
                BASE.plusSeconds(i)
            ));
        }
        Path file = store.sessionFile("ses_large");
        Files.writeString(file, "{bad json}\n", StandardOpenOption.APPEND);

        assertThatThrownBy(() -> store.read("ses_large"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("line 5002");
    }

    @Test
    void readReportsEarlierMalformedEntryBeforeLaterUnreadableBytes() throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_streaming_error"));
        Path file = store.sessionFile("ses_streaming_error");
        Files.write(file, "{bad json}\n".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(file, " ".repeat(20_000).getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28}, StandardOpenOption.APPEND);

        assertThatThrownBy(() -> store.read("ses_streaming_error"))
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("line 2");
    }

    @Test
    void headersDoNotDecodeEntriesAfterTheHeader() throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        store.create(sessionHeader("ses_header_streaming"));
        Path file = store.sessionFile("ses_header_streaming");
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28}, StandardOpenOption.APPEND);

        assertThat(store.headers())
            .extracting(SessionHeader::id)
            .containsExactly("ses_header_streaming");
    }

    @Test
    void headersRejectMalformedUtf8InsideHeaderLine() throws Exception {
        JsonlSessionStore store = new JsonlSessionStore(tempDir);
        Path file = store.sessionFile("ses_bad_header");
        Files.createDirectories(file.getParent());
        Files.write(file, "{\"type\":\"session\",\"version\":1,\"id\":\"ses_".getBytes(StandardCharsets.UTF_8));
        Files.write(file, new byte[] {(byte) 0xC3, (byte) 0x28}, StandardOpenOption.APPEND);
        Files.write(
            file,
            ("\",\"cwd\":\"" + tempDir + "\",\"parentSessionId\":null,\"timestamp\":\"2026-06-23T00:00:00Z\"}\n")
                .getBytes(StandardCharsets.UTF_8),
            StandardOpenOption.APPEND
        );

        assertThatThrownBy(store::headers)
            .isInstanceOf(SessionEngineException.class)
            .hasMessageContaining("Failed to read session header");
    }

    private SessionHeader sessionHeader(String id) {
        return new SessionHeader("session", 1, id, tempDir, Optional.empty(), BASE);
    }
}
