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

    static byte[] png1x1() {
        return Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=");
    }
}
