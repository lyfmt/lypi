package cn.lypi.tool.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class ImageReadProcessorTest {
    @Test
    void createsImagePayloadWithMetadata() {
        ImageReadProcessor.Result result = ImageReadProcessor.process(png1x1(), "image/png");

        assertTrue(result.imageUrl().startsWith("data:image/png;base64,"));
        assertEquals("image/png", result.mediaType());
        assertTrue(result.sizeBytes() > 0);
        assertEquals(1, result.metadata().get("originalWidth"));
        assertEquals(1, result.metadata().get("originalHeight"));
        assertEquals(1, result.metadata().get("displayWidth"));
        assertEquals(1, result.metadata().get("displayHeight"));
        assertEquals(false, result.metadata().get("resized"));
    }

    @Test
    void rejectsEmptyImage() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> ImageReadProcessor.process(new byte[0], "image/png")
        );
        assertTrue(exception.getMessage().contains("图片文件为空"));
    }

    @Test
    void doesNotMarkFallbackBytesAsResizedWhenImageCannotBeDecoded() {
        byte[] bytes = new byte[ImageReadProcessor.TARGET_IMAGE_BYTES + 1];
        bytes[0] = (byte) 0x89;
        bytes[1] = 0x50;
        bytes[2] = 0x4E;
        bytes[3] = 0x47;
        bytes[4] = 0x0D;
        bytes[5] = 0x0A;
        bytes[6] = 0x1A;
        bytes[7] = 0x0A;

        ImageReadProcessor.Result result = ImageReadProcessor.process(bytes, "image/png");

        assertEquals(false, result.metadata().get("resized"));
        assertEquals(bytes.length, result.sizeBytes());
    }

    static byte[] png1x1() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    }
}
