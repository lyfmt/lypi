package cn.lypi.tool.builtin;

import java.util.Locale;
import java.util.Set;

final class BinaryFileDetector {
    private static final int SAMPLE_BYTES = 8 * 1024;
    private static final Set<String> UNSUPPORTED_BINARY_EXTENSIONS = Set.of(
        ".zip", ".jar", ".class", ".exe", ".so", ".dll", ".bin"
    );
    private static final Set<String> RESERVED_FORMAT_EXTENSIONS = Set.of(".pdf", ".ipynb");

    private BinaryFileDetector() {
    }

    static boolean isUnsupportedBinary(String fileName, byte[] bytes) {
        if (ImageFileDetector.detect(bytes, fileName).isPresent()) {
            return false;
        }
        String lowerFileName = fileName.toLowerCase(Locale.ROOT);
        if (hasAnyExtension(lowerFileName, RESERVED_FORMAT_EXTENSIONS)) {
            return false;
        }
        if (hasAnyExtension(lowerFileName, UNSUPPORTED_BINARY_EXTENSIONS)) {
            return true;
        }
        int sampleLength = Math.min(bytes.length, SAMPLE_BYTES);
        for (int index = 0; index < sampleLength; index++) {
            if (bytes[index] == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasAnyExtension(String fileName, Set<String> extensions) {
        for (String extension : extensions) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }
}
