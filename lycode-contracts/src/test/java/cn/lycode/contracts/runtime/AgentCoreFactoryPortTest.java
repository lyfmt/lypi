package cn.lycode.contracts.runtime;

import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.lycode.contracts.event.EventBus;
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
        public void publish(cn.lycode.contracts.event.AgentEvent event) {
        }

        @Override
        public cn.lycode.contracts.event.EventSubscription subscribe(
            cn.lycode.contracts.event.EventFilter filter,
            cn.lycode.contracts.event.EventConsumer consumer
        ) {
            return () -> {
            };
        }
    }
}
