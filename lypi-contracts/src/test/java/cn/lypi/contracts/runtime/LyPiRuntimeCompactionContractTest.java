package cn.lypi.contracts.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LyPiRuntimeCompactionContractTest {
    @Test
    void runtimeCarriesManualCompactionPort() {
        CompactionRuntimePort compactionRuntime = request -> new CompactionResult(
            true,
            Optional.of("entry-compact-1"),
            "compacted"
        );

        LyPiRuntime runtime = new LyPiRuntime(
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            compactionRuntime,
            List.of()
        );

        CompactionResult result = runtime.compactionRuntime().compact(new CompactionRequest(
            "ses_1",
            Optional.of("leaf_1"),
            Path.of("."),
            () -> false
        ));

        assertSame(compactionRuntime, runtime.compactionRuntime());
        assertTrue(result.compacted());
        assertEquals(Optional.of("entry-compact-1"), result.entryId());
        assertEquals("compacted", result.message());
    }
}
