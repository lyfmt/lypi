package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
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
                .map(this::readHeaderFile)
                .toList();
        } catch (IOException e) {
            throw new SessionEngineException("Failed to list session headers: " + sessionsDir, e);
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
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                throw new SessionEngineException("Session file is empty: " + file);
            }
            SessionHeader header = readHeaderLine(file, lines.getFirst());
            validateHeader(header);
            return header;
        } catch (IOException e) {
            throw new SessionEngineException("Failed to read session header: " + file, e);
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
    }
}
