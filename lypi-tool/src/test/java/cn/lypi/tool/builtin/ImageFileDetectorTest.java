package cn.lypi.tool.builtin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ImageFileDetectorTest {
    @Test
    void detectsSupportedImageMagicBytes() {
        assertEquals(Optional.of("image/png"), ImageFileDetector.detect(pngBytes(), "a.png"));
        assertEquals(Optional.of("image/jpeg"), ImageFileDetector.detect(jpegBytes(), "a.jpg"));
        assertEquals(Optional.of("image/gif"), ImageFileDetector.detect(gifBytes(), "a.gif"));
        assertEquals(Optional.of("image/webp"), ImageFileDetector.detect(webpBytes(), "a.webp"));
    }

    @Test
    void rejectsNonImages() {
        assertEquals(Optional.empty(), ImageFileDetector.detect("hello".getBytes(UTF_8), "notes.txt"));
    }

    @Test
    void magicBytesWinOverExtension() {
        assertEquals(Optional.of("image/png"), ImageFileDetector.detect(pngBytes(), "a.jpg"));
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
    }

    private static byte[] jpegBytes() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00};
    }

    private static byte[] gifBytes() {
        return "GIF89a".getBytes(UTF_8);
    }

    private static byte[] webpBytes() {
        return new byte[] {
            0x52, 0x49, 0x46, 0x46,
            0x00, 0x00, 0x00, 0x00,
            0x57, 0x45, 0x42, 0x50
        };
    }
}
