package cn.lypi.contracts.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lypi.contracts.event.EventBus;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentCoreFactoryPortTest {
    @Test
    void restrictedCoreCreationFailsFastWhenFactoryDoesNotSupportIt() {
        AgentCoreFactoryPort factory = (cwd, sessionManager) -> {
            throw new AssertionError("ordinary factory must not be used");
        };

        assertThrows(UnsupportedOperationException.class, () -> factory.create(
            Path.of("."),
            null,
            null,
            new NoopEventBus()
        ));
    }

    private static final class NoopEventBus implements EventBus {
        @Override
        public void publish(cn.lypi.contracts.event.AgentEvent event) {
        }

        @Override
        public cn.lypi.contracts.event.EventSubscription subscribe(
            cn.lypi.contracts.event.EventFilter filter,
            cn.lypi.contracts.event.EventConsumer consumer
        ) {
            return () -> {
            };
        }
    }
}
