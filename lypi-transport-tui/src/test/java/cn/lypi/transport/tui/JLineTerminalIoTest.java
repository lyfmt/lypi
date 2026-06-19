package cn.lypi.transport.tui;

import java.io.IOError;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import org.jline.terminal.Attributes;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JLineTerminalIoTest {
    @Test
    void fallsBackWhenTerminalHeightLookupFailsDuringInterrupt() {
        Terminal terminal = terminalThrowingOnSize(new IOError(new IOException("stty interrupted")));

        JLineTerminalIo io = new JLineTerminalIo(terminal);

        assertEquals(24, io.height());
    }

    @Test
    void fallsBackWhenTerminalWidthLookupFailsDuringInterrupt() {
        Terminal terminal = terminalThrowingOnSize(new IOError(new IOException("stty interrupted")));

        JLineTerminalIo io = new JLineTerminalIo(terminal);

        assertEquals(80, io.width());
    }

    private static Terminal terminalThrowingOnSize(IOError error) {
        StringWriter writer = new StringWriter();
        return (Terminal) Proxy.newProxyInstance(
            Terminal.class.getClassLoader(),
            new Class<?>[] {Terminal.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getSize", "getWidth", "getHeight" -> throw error;
                case "writer" -> new PrintWriter(writer);
                case "output" -> OutputStream.nullOutputStream();
                case "encoding", "inputEncoding", "outputEncoding", "stdinEncoding", "stdoutEncoding" -> StandardCharsets.UTF_8;
                case "enterRawMode", "getAttributes" -> new Attributes();
                case "handle" -> Terminal.SignalHandler.SIG_DFL;
                case "getName" -> "test";
                case "getType" -> Terminal.TYPE_DUMB;
                default -> defaultValue(method.getReturnType());
            }
        );
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == void.class) {
            return null;
        }
        if (type == Size.class) {
            return new Size(80, 24);
        }
        return null;
    }
}
