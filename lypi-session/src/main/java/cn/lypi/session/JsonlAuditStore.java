package cn.lypi.session;

import cn.lypi.contracts.audit.AuditRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

final class JsonlAuditStore {
    private final Path auditFile;
    private final ObjectMapper objectMapper;

    JsonlAuditStore(Path cwd) {
        this(cwd.resolve(".lypi").resolve("audit.jsonl"), new SessionJsonMapper().objectMapper());
    }

    JsonlAuditStore(Path auditFile, ObjectMapper objectMapper) {
        this.auditFile = auditFile.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    /**
     * 追加 audit JSONL 记录。
     */
    void append(AuditRecord record) {
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(
                auditFile,
                objectMapper.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new SessionEngineException("Failed to append audit record: " + record.auditId(), e);
        }
    }

    /**
     * 读取所有 audit JSONL 记录。
     */
    List<AuditRecord> readAll() {
        if (!Files.exists(auditFile)) {
            return List.of();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(auditFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SessionEngineException("Failed to read audit file: " + auditFile, e);
        }
        List<AuditRecord> records = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank()) {
                records.add(readLine(line, i + 1));
            }
        }
        return List.copyOf(records);
    }

    private AuditRecord readLine(String line, int lineNumber) {
        try {
            return objectMapper.readValue(line, AuditRecord.class);
        } catch (JsonProcessingException e) {
            throw new SessionEngineException("Failed to read audit JSONL line " + lineNumber + ": " + e.getOriginalMessage(), e);
        }
    }
}
