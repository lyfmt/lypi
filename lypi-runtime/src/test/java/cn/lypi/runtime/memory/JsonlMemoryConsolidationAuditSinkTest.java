package cn.lypi.runtime.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonlMemoryConsolidationAuditSinkTest {
    @TempDir
    Path tempDir;

    @Test
    void appendsAuditRecordToProjectLyPiJsonl() throws Exception {
        JsonlMemoryConsolidationAuditSink sink = new JsonlMemoryConsolidationAuditSink(tempDir);

        sink.record(new MemoryConsolidationAuditRecord(
            MemoryConsolidationAuditStage.FORK_CREATED,
            "ses_main",
            "turn_1",
            "entry_leaf",
            "ses_fork",
            1_200_000L,
            31,
            "forked",
            null,
            Instant.parse("2026-06-01T00:00:00Z")
        ));

        Path auditFile = tempDir.resolve(".ly-pi").resolve("memory-consolidation-audit.jsonl");
        assertThat(auditFile).exists();
        assertThat(Files.readString(auditFile))
            .contains("\"stage\":\"FORK_CREATED\"")
            .contains("\"sessionId\":\"ses_main\"")
            .contains("\"turnId\":\"turn_1\"")
            .contains("\"forkPointEntryId\":\"entry_leaf\"")
            .contains("\"forkSessionId\":\"ses_fork\"")
            .contains("\"reason\":\"forked\"");
    }

    @Test
    void ignoresNullRecord() throws Exception {
        JsonlMemoryConsolidationAuditSink sink = new JsonlMemoryConsolidationAuditSink(tempDir);

        sink.record(null);

        assertThat(tempDir.resolve(".ly-pi").resolve("memory-consolidation-audit.jsonl")).doesNotExist();
    }
}
