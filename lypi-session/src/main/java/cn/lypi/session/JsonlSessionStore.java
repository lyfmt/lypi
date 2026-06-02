package cn.lypi.session;

import cn.lypi.contracts.session.SessionEntry;
import cn.lypi.contracts.session.SessionHeader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class JsonlSessionStore {
    private static final int SUPPORTED_SESSION_VERSION = 1;
    private final Path sessionsDir;
    private final SessionJsonMapper mapper;

    JsonlSessionStore(Path cwd) {
        this(cwd.resolve(".lypi").resolve("sessions"), new SessionJsonMapper());
    }

    JsonlSessionStore(Path sessionsDir, SessionJsonMapper mapper) {
        this.sessionsDir = sessionsDir.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

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

    void create(SessionHeader header) {
        Path file = sessionFile(header.id());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                file,
                mapper.writeHeader(header) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW
            );
        } catch (FileAlreadyExistsException e) {
            throw new SessionEngineException("Session file already exists: " + file, e);
        } catch (IOException e) {
            throw new SessionEngineException("Failed to create session file: " + file, e);
        }
    }

    SessionFile read(String sessionId) {
        Path file = sessionFile(sessionId);
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SessionEngineException("Failed to read session file: " + file, e);
        }
        if (lines.isEmpty()) {
            throw new SessionEngineException("Session file is empty: " + file);
        }
        SessionHeader header = readHeaderLine(file, lines.get(0));
        validateHeader(header);
        List<SessionEntry> entries = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                entries.add(readEntryLine(file, line, i + 1));
            }
        }
        return new SessionFile(header, List.copyOf(entries));
    }

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

    private SessionHeader readHeaderLine(Path file, String line) {
        try {
            return mapper.readHeader(mapper.readEnvelope(line));
        } catch (SessionEngineException e) {
            throw new SessionEngineException("Failed to read session JSONL line 1 from " + file + ": " + e.getMessage(), e);
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
