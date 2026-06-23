package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.stream.Stream;

/**
 * 读写 append-only session JSONL 文件。
 *
 * NOTE: store 只处理文件格式和版本校验，不维护 entry tree 语义。
 */
final class JsonlSessionStore {
    private static final int SUPPORTED_SESSION_VERSION = 1;
    private final Path sessionsDir;
    private final SessionJsonMapper mapper;

    JsonlSessionStore(Path cwd) {
        this(cwd.resolve(".ly-pi").resolve("sessions"), new SessionJsonMapper());
    }

    JsonlSessionStore(Path sessionsDir, SessionJsonMapper mapper) {
        this.sessionsDir = sessionsDir.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    /**
     * 返回 session id 对应的 JSONL 文件路径。
     */
    Path sessionFile(String sessionId) {
        SessionIdValidator.validate(sessionId);
        Path file = sessionsDir.resolve(sessionId + ".jsonl").normalize();
        if (!file.startsWith(sessionsDir)) {
            throw new SessionEngineException("Invalid session id: " + sessionId);
        }
        return file;
    }

    boolean exists(String sessionId) {
        return Files.exists(sessionFile(sessionId));
    }

    /**
     * 删除指定 session 的 JSONL 文件。
     *
     * NOTE: 删除不存在的 session 是幂等 no-op。
     */
    void delete(String sessionId) {
        Path file = sessionFile(sessionId);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new SessionEngineException("Failed to delete session file: " + file, e);
        }
    }

    /**
     * 创建新的 session 文件并写入 header。
     */
    void create(SessionHeader header) {
        if (!tryCreate(header)) {
            throw new SessionEngineException("Session file already exists: " + sessionFile(header.id()));
        }
    }

    /**
     * 尝试创建新的 session 文件并写入 header。
     */
    boolean tryCreate(SessionHeader header) {
        Path file = sessionFile(header.id());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                file,
                mapper.writeHeader(header) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
            );
            return true;
        } catch (FileAlreadyExistsException e) {
            return false;
        } catch (IOException e) {
            throw new SessionEngineException("Failed to create session file: " + file, e);
        }
    }

    /**
     * 读取 session 文件并解析 header 与 entries。
     */
    SessionFile read(String sessionId) {
        Path file = sessionFile(sessionId);
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new SessionEngineException("Session file is empty: " + file);
            }
            SessionHeader header = readHeaderLine(file, headerLine);
            validateHeader(header);
            validateSessionFileHeader(file, header);
            List<SessionEntry> entries = new ArrayList<>();
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (!line.isBlank()) {
                    entries.add(readEntryLine(file, line, lineNumber));
                }
            }
            return new SessionFile(header, List.copyOf(entries));
        } catch (IOException e) {
            throw new SessionEngineException("Failed to read session file: " + file, e);
        }
    }

    /**
     * 读取 sessions 目录下所有 session header。
     */
    List<SessionHeader> headers() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                .filter(file -> file.getFileName().toString().endsWith(".jsonl"))
                .sorted()
                .map(this::tryReadHeaderFile)
                .flatMap(Optional::stream)
                .toList();
        } catch (IOException e) {
            throw new SessionEngineException("Failed to list session headers: " + sessionsDir, e);
        }
    }

    /**
     * 逐文件扫描 resume 所需的轻量 metadata。
     */
    List<SessionResumeScan> resumeScans() {
        if (!Files.isDirectory(sessionsDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(sessionsDir)) {
            return files
                .filter(file -> file.getFileName().toString().endsWith(".jsonl"))
                .sorted()
                .map(this::resumeScan)
                .flatMap(Optional::stream)
                .toList();
        } catch (IOException e) {
            throw new SessionEngineException("Failed to list session resume metadata: " + sessionsDir, e);
        }
    }

    Optional<SessionResumeScan> resumeScan(Path file) {
        try {
            return Optional.of(readResumeScan(file));
        } catch (SessionEngineException exception) {
            return Optional.empty();
        }
    }

    /**
     * 追加一条 entry JSONL 行。
     */
    void append(String sessionId, SessionEntry entry) {
        Path file = sessionFile(sessionId);
        try {
            Files.writeString(
                file,
                mapper.writeEntry(entry) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new SessionEngineException("Failed to append session entry: " + entry.id(), e);
        }
    }

    /**
     * 创建新的 session 文件并按顺序写入 header 和 entries。
     */
    void createWithEntries(SessionHeader header, List<SessionEntry> entries) {
        create(header);
        for (SessionEntry entry : entries) {
            append(header.id(), entry);
        }
    }

    private SessionHeader readHeaderLine(Path file, String line) {
        try {
            return mapper.readHeader(mapper.readEnvelope(line));
        } catch (SessionEngineException e) {
            throw new SessionEngineException("Failed to read session JSONL line 1 from " + file + ": " + e.getMessage(), e);
        }
    }

    private SessionHeader readHeaderFile(Path file) {
        try {
            String line = readFirstLine(file);
            if (line == null) {
                throw new SessionEngineException("Session file is empty: " + file);
            }
            SessionHeader header = readHeaderLine(file, line);
            validateHeader(header);
            validateSessionFileHeader(file, header);
            return header;
        } catch (IOException e) {
            throw new SessionEngineException("Failed to read session header: " + file, e);
        }
    }

    private Optional<SessionHeader> tryReadHeaderFile(Path file) {
        try {
            return Optional.of(readHeaderFile(file));
        } catch (SessionEngineException exception) {
            return Optional.empty();
        }
    }

    private SessionResumeScan readResumeScan(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new SessionEngineException("Session file is empty: " + file);
            }
            SessionHeader header = readHeaderLine(file, headerLine);
            validateHeader(header);
            validateSessionFileHeader(file, header);
            String leafId = null;
            Instant modified = null;
            int messageCount = 0;
            String firstMessage = null;
            StringJoiner allMessagesText = new StringJoiner(" ");
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                SessionEntry entry = readEntryLine(file, line, lineNumber);
                if (SessionLeafSelector.advancesNavigableLeaf(entry)) {
                    leafId = entry.id();
                }
                if (entry.timestamp() != null && (modified == null || entry.timestamp().isAfter(modified))) {
                    modified = entry.timestamp();
                }
                String text = SessionEntryDisplayText.text(entry);
                if (!text.isBlank()) {
                    messageCount++;
                    if (firstMessage == null) {
                        firstMessage = text;
                    }
                    allMessagesText.add(text);
                }
            }
            return new SessionResumeScan(
                header,
                file,
                leafId,
                modified == null ? header.timestamp() : modified,
                messageCount,
                firstMessage == null ? "(no messages)" : firstMessage,
                allMessagesText.toString()
            );
        } catch (IOException e) {
            throw new SessionEngineException("Failed to scan session resume metadata: " + file, e);
        }
    }

    private String readFirstLine(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    break;
                }
                line.write(value);
            }
            if (value == -1 && line.size() == 0) {
                return null;
            }
            byte[] bytes = line.toByteArray();
            int length = bytes.length;
            if (length > 0 && bytes[length - 1] == '\r') {
                length--;
            }
            return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, 0, length))
                .toString();
        }
    }

    private SessionEntry readEntryLine(Path file, String line, int lineNumber) {
        try {
            return mapper.readEntry(mapper.readEnvelope(line));
        } catch (SessionEngineException e) {
            throw new SessionEngineException(
                "Failed to read session JSONL line " + lineNumber + " from " + file + ": " + e.getMessage(),
                e
            );
        }
    }

    private void validateHeader(SessionHeader header) {
        if (header.version() != SUPPORTED_SESSION_VERSION) {
            throw new SessionEngineException("Unsupported session version: " + header.version());
        }
        if (header.id() == null || header.cwd() == null || header.timestamp() == null) {
            throw new SessionEngineException("Session header is missing required fields: " + header.id());
        }
    }

    private void validateSessionFileHeader(Path file, SessionHeader header) {
        Path expected = sessionFile(header.id());
        if (!expected.equals(file.toAbsolutePath().normalize())) {
            throw new SessionEngineException("Session header id does not match file: " + file);
        }
    }
}
