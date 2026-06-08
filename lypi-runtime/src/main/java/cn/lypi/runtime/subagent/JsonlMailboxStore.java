package cn.lypi.runtime.subagent;

import cn.lypi.contracts.subagent.MailboxMessage;
import cn.lypi.contracts.subagent.MailboxStatus;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class JsonlMailboxStore {
    private final Path mailboxDir;
    private final ObjectMapper objectMapper;

    public JsonlMailboxStore(Path cwd) {
        this.mailboxDir = cwd.resolve(".lypi").resolve("mailbox").toAbsolutePath().normalize();
        objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        objectMapper.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        objectMapper.registerModule(new Jdk8Module());
        objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * 追加 mailbox 消息状态行。
     */
    public synchronized void append(MailboxMessage message) {
        try {
            Files.createDirectories(mailboxDir);
            Files.writeString(
                mailboxFile(message.parentSessionId()),
                objectMapper.writeValueAsString(message) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to append mailbox message: " + message.mailId(), e);
        }
    }

    /**
     * 读取指定 session mailbox 的最新状态投影。
     */
    public synchronized List<MailboxMessage> read(String parentSessionId, Set<MailboxStatus> statuses) {
        Path file = mailboxFile(parentSessionId);
        if (!Files.exists(file)) {
            return List.of();
        }
        return readFile(file, statuses);
    }

    /**
     * 按 child session id 查询已持久化 mailbox 最新状态。
     */
    public synchronized Optional<MailboxMessage> findByChildSessionId(String childSessionId) {
        if (childSessionId == null || childSessionId.isBlank() || !Files.exists(mailboxDir)) {
            return Optional.empty();
        }
        try (java.util.stream.Stream<Path> files = Files.list(mailboxDir)) {
            return files
                .filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                .flatMap(path -> readFile(path, Set.of()).stream())
                .filter(message -> childSessionId.equals(message.childSessionId()))
                .max(java.util.Comparator.comparing(MailboxMessage::updatedAt));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to scan mailbox for child session: " + childSessionId, e);
        }
    }

    private List<MailboxMessage> readFile(Path file, Set<MailboxStatus> statuses) {
        Map<String, MailboxMessage> latestById = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    MailboxMessage message = objectMapper.readValue(line, MailboxMessage.class);
                    latestById.put(message.mailId(), message);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read mailbox file: " + file, e);
        }
        return latestById.values().stream()
            .filter(message -> statuses == null || statuses.isEmpty() || statuses.contains(message.status()))
            .toList();
    }

    private Path mailboxFile(String parentSessionId) {
        if (parentSessionId == null || parentSessionId.isBlank() || !parentSessionId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid mailbox session id: " + parentSessionId);
        }
        Path file = mailboxDir.resolve(parentSessionId + ".jsonl").normalize();
        if (!file.startsWith(mailboxDir)) {
            throw new IllegalArgumentException("Invalid mailbox session id: " + parentSessionId);
        }
        return file;
    }
}
