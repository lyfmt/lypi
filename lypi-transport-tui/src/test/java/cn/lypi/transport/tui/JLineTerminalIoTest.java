package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

class JLineTerminalIoTest {
    @Test
    void enterRawModeDisablesControlCharacterEcho() {
        RecordingTerminal terminal = new RecordingTerminal();
        JLineTerminalIo io = new JLineTerminalIo(terminal.proxy());

        io.enterRawMode();

        assertNotNull(terminal.updated);
        assertFalse(terminal.updated.getLocalFlag(Attributes.LocalFlag.ECHO));
        assertFalse(terminal.updated.getLocalFlag(Attributes.LocalFlag.ECHOCTL));
        assertTrue(terminal.previous.getLocalFlag(Attributes.LocalFlag.ECHOCTL));
    }

    private static final class RecordingTerminal {
        private final Attributes previous = new Attributes();
        private Attributes updated;

        private RecordingTerminal() {
            previous.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            previous.setLocalFlag(Attributes.LocalFlag.ECHOCTL, true);
        }

        private Terminal proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "enterRawMode" -> new Attributes(previous);
                case "setAttributes" -> {
                    updated = new Attributes((Attributes) args[0]);
                    yield null;
                }
                case "getWidth" -> 80;
                case "getHeight" -> 24;
                case "writer" -> new java.io.PrintWriter(java.io.Writer.nullWriter());
                case "handle" -> args[1];
                default -> defaultValue(method.getReturnType());
            };
            return (Terminal) Proxy.newProxyInstance(
                Terminal.class.getClassLoader(),
                new Class<?>[] { Terminal.class },
                handler
            );
        }

        private Object defaultValue(Class<?> type) {
            if (type == boolean.class) {
                return false;
            }
            if (type == int.class) {
                return 0;
            }
            if (type == long.class) {
                return 0L;
            }
            if (type == void.class) {
                return null;
            }
            return null;
        }
    }
}
