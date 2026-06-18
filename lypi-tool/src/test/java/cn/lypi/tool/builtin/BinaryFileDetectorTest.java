package cn.lypi.tool.builtin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BinaryFileDetectorTest {
    @Test
    void rejectsUnknownBinaryButNotImagesOrText() {
        assertTrue(BinaryFileDetector.isUnsupportedBinary("archive.zip", zipHeader()));
        assertFalse(BinaryFileDetector.isUnsupportedBinary("image.png", pngBytes()));
        assertFalse(BinaryFileDetector.isUnsupportedBinary("notes.txt", "hello".getBytes(UTF_8)));
    }

    @Test
    void rejectsNulByteBinaryIncludingReservedFutureFormats() {
        assertTrue(BinaryFileDetector.isUnsupportedBinary("blob.dat", new byte[] {'a', 0, 'b'}));
        assertTrue(BinaryFileDetector.isUnsupportedBinary("paper.pdf", new byte[] {'%', 'P', 'D', 'F', 0}));
        assertTrue(BinaryFileDetector.isUnsupportedBinary("notes.ipynb", new byte[] {'{', 0, '}'}));
    }

    private static byte[] zipHeader() {
        return new byte[] {'P', 'K', 0x03, 0x04};
    }

    private static byte[] pngBytes() {
        return new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    }
}
