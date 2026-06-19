package cn.lypi.tool.builtin;

import java.util.Optional;

final class ImageFileDetector {
    private static final byte[] PNG_SIGNATURE = new byte[] {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final byte[] GIF87A_SIGNATURE = new byte[] {
        0x47, 0x49, 0x46, 0x38, 0x37, 0x61
    };
    private static final byte[] GIF89A_SIGNATURE = new byte[] {
        0x47, 0x49, 0x46, 0x38, 0x39, 0x61
    };

    private ImageFileDetector() {
    }

    static Optional<String> detect(byte[] bytes, String fileName) {
        if (startsWith(bytes, PNG_SIGNATURE)) {
            return Optional.of("image/png");
        }
        if (bytes.length >= 3
            && (bytes[0] & 0xFF) == 0xFF
            && (bytes[1] & 0xFF) == 0xD8
            && (bytes[2] & 0xFF) == 0xFF) {
            return Optional.of("image/jpeg");
        }
        if (startsWith(bytes, GIF87A_SIGNATURE) || startsWith(bytes, GIF89A_SIGNATURE)) {
            return Optional.of("image/gif");
        }
        if (bytes.length >= 12
            && bytes[0] == 0x52
            && bytes[1] == 0x49
            && bytes[2] == 0x46
            && bytes[3] == 0x46
            && bytes[8] == 0x57
            && bytes[9] == 0x45
            && bytes[10] == 0x42
            && bytes[11] == 0x50) {
            return Optional.of("image/webp");
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }
}
