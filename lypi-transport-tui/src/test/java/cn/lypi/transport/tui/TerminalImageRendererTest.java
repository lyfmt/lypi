package cn.lypi.transport.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TerminalImageRendererTest {
    @Test
    void detectsPngGifAndJpegDimensions() {
        assertEquals(new ImageDimensions(1, 1), TerminalImageRenderer.dimensions(png1x1()));
        assertEquals(new ImageDimensions(2, 3), TerminalImageRenderer.dimensions(gif2x3()));
        assertEquals(new ImageDimensions(4, 5), TerminalImageRenderer.dimensions(jpeg4x5()));
    }

    @Test
    void rendersKittyAndIterm2Escapes() {
        TerminalImageRenderer renderer = new TerminalImageRenderer();

        String kitty = renderer.renderKitty(png1x1());
        String iterm = renderer.renderIterm2("image.png", png1x1());

        assertTrue(kitty.startsWith("\033_Gf=100"));
        assertTrue(kitty.endsWith("\033\\"));
        assertTrue(iterm.startsWith("\033]1337;File=name="));
        assertTrue(iterm.endsWith("\007"));
    }

    @Test
    void markdownDataUriFallsBackToReadablePlaceholder() {
        TerminalImageRenderer renderer = new TerminalImageRenderer();
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(png1x1());

        assertEquals("[image 1x1 image/png]", renderer.renderMarkdownDataUri(dataUri));
    }

    private byte[] png1x1() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lw9V9QAAAABJRU5ErkJggg=="
        );
    }

    private byte[] gif2x3() {
        return new byte[] {
            'G', 'I', 'F', '8', '9', 'a',
            2, 0, 3, 0,
            0, 0, 0
        };
    }

    private byte[] jpeg4x5() {
        return new byte[] {
            (byte) 0xFF, (byte) 0xD8,
            (byte) 0xFF, (byte) 0xC0,
            0, 17,
            8,
            0, 5,
            0, 4,
            1, 1, 0, 0, 0,
            (byte) 0xFF, (byte) 0xD9
        };
    }
}
