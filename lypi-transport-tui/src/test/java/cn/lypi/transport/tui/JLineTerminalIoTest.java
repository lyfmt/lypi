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
    void enterRawModeDisablesControlCharacterEcho() throws Exception {
        RecordingTerminal terminal = new RecordingTerminal();
        RecordingTerminalModeController modes = new RecordingTerminalModeController();
        JLineTerminalIo io = new JLineTerminalIo(terminal.proxy(), modes);

        AutoCloseable restore = io.enterRawMode();

        assertTrue(modes.entered);
        assertNotNull(terminal.updated);
        assertFalse(terminal.updated.getLocalFlag(Attributes.LocalFlag.ECHO));
        assertFalse(terminal.updated.getLocalFlag(Attributes.LocalFlag.ECHOCTL));
        assertFalse(terminal.updated.getLocalFlag(Attributes.LocalFlag.ICANON));
        assertTrue(terminal.updated.getControlChar(Attributes.ControlChar.VDISCARD) < 0);
        assertTrue(terminal.previous.getLocalFlag(Attributes.LocalFlag.ECHOCTL));

        restore.close();

        assertTrue(modes.restored);
    }

    private static final class RecordingTerminal {
        private final Attributes previous = new Attributes();
        private final Attributes raw = new Attributes();
        private Attributes updated;

        private RecordingTerminal() {
            previous.setLocalFlag(Attributes.LocalFlag.ECHO, true);
            previous.setLocalFlag(Attributes.LocalFlag.ECHOCTL, true);
            previous.setLocalFlag(Attributes.LocalFlag.ICANON, true);
            raw.setLocalFlag(Attributes.LocalFlag.ECHO, false);
            raw.setLocalFlag(Attributes.LocalFlag.ECHOCTL, true);
            raw.setLocalFlag(Attributes.LocalFlag.ICANON, false);
            raw.setControlChar(Attributes.ControlChar.VDISCARD, 15);
        }

        private Terminal proxy() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "enterRawMode" -> new Attributes(previous);
                case "getAttributes" -> new Attributes(raw);
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

    private static final class RecordingTerminalModeController implements JLineTerminalIo.TerminalModeController {
        private boolean entered;
        private boolean restored;

        @Override
        public AutoCloseable enterRawMode() {
            entered = true;
            return () -> restored = true;
        }
    }
}
