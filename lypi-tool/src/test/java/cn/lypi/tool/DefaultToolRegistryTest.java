package cn.lypi.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lypi.contracts.tool.Tool;
import cn.lypi.contracts.tool.ToolDescriptor;
import cn.lypi.contracts.tool.ToolRegistrySnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DefaultToolRegistryTest {
    @Test
    void resolvesToolByPrimaryNameAndAlias() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        Tool<Map<String, Object>, String> tool = TestTools.echo("read", List.of("cat"), true, true, false);

        registry.register(tool);

        assertSame(tool, registry.resolve("read").orElseThrow());
        assertSame(tool, registry.resolve("cat").orElseThrow());
    }

    @Test
    void rejectsNameAndAliasConflicts() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(TestTools.echo("read", List.of("cat"), true, true, false));

        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(TestTools.echo("cat", List.of(), true, true, false))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> registry.register(TestTools.echo("grep", List.of("read"), true, true, false))
        );
    }

    @Test
    void snapshotDoesNotExposeToolInstances() {
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(TestTools.echo("read", List.of("cat"), true, true, false));

        ToolRegistrySnapshot snapshot = registry.snapshot();

        assertEquals(List.of(new ToolDescriptor("read", List.of("cat"), true, false)), snapshot.tools());
    }
}
