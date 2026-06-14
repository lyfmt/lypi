package cn.lypi.runtime.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * 将后台记忆沉淀审计记录追加到项目本地 JSONL 文件。
 */
public final class JsonlMemoryConsolidationAuditSink implements MemoryConsolidationAuditSink {
    public static final String FILE_NAME = "memory-consolidation-audit.jsonl";

    private final Path auditFile;
    private final ObjectMapper mapper;

    public JsonlMemoryConsolidationAuditSink(Path cwd) {
        this(cwd, new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule()));
    }

    JsonlMemoryConsolidationAuditSink(Path cwd, ObjectMapper mapper) {
        Path normalizedCwd = Objects.requireNonNull(cwd, "cwd must not be null").toAbsolutePath().normalize();
        this.auditFile = normalizedCwd.resolve(".ly-pi").resolve(FILE_NAME);
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * 追加审计记录。
     */
    @Override
    public synchronized void record(MemoryConsolidationAuditRecord record) {
        if (record == null) {
            return;
        }
        try {
            Files.createDirectories(auditFile.getParent());
            Files.writeString(
                auditFile,
                mapper.writeValueAsString(record) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            );
        } catch (IOException | RuntimeException exception) {
            // NOTE: 审计是诊断辅助，失败不能影响主流程。
        }
    }
}
