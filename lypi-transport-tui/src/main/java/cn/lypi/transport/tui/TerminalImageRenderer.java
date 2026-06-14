package cn.lypi.transport.tui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class TerminalImageRenderer {
    static ImageDimensions dimensions(byte[] bytes) {
        if (bytes == null || bytes.length < 10) {
            return new ImageDimensions(0, 0);
        }
        if (isPng(bytes)) {
            return new ImageDimensions(readInt(bytes, 16), readInt(bytes, 20));
        }
        if (isGif(bytes)) {
            return new ImageDimensions(readShortLittle(bytes, 6), readShortLittle(bytes, 8));
        }
        if (isJpeg(bytes)) {
            return jpegDimensions(bytes);
        }
        return new ImageDimensions(0, 0);
    }

    String renderKitty(byte[] bytes) {
        return "\033_Gf=100,a=T,m=0;" + Base64.getEncoder().encodeToString(bytes) + "\033\\";
    }

    String renderIterm2(String name, byte[] bytes) {
        String encodedName = Base64.getEncoder().encodeToString((name == null ? "image" : name).getBytes(StandardCharsets.UTF_8));
        return "\033]1337;File=name=" + encodedName + ";inline=1:"
            + Base64.getEncoder().encodeToString(bytes) + "\007";
    }

    String renderMarkdownDataUri(String dataUri) {
        if (dataUri == null || !dataUri.startsWith("data:") || !dataUri.contains(",")) {
            return "[image]";
        }
        int mediaEnd = dataUri.indexOf(';');
        int comma = dataUri.indexOf(',');
        String mediaType = dataUri.substring("data:".length(), mediaEnd > 0 ? mediaEnd : comma);
        byte[] data = Base64.getDecoder().decode(dataUri.substring(comma + 1));
        ImageDimensions dimensions = dimensions(data);
        return "[image " + dimensions.width() + "x" + dimensions.height() + " " + mediaType + "]";
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 24
            && bytes[0] == (byte) 0x89
            && bytes[1] == 'P'
            && bytes[2] == 'N'
            && bytes[3] == 'G';
    }

    private static boolean isGif(byte[] bytes) {
        return bytes.length >= 10
            && bytes[0] == 'G'
            && bytes[1] == 'I'
            && bytes[2] == 'F';
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes.length >= 4 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8;
    }

    private static ImageDimensions jpegDimensions(byte[] bytes) {
        int index = 2;
        while (index + 8 < bytes.length) {
            if (bytes[index] != (byte) 0xFF) {
                index++;
                continue;
            }
            int marker = bytes[index + 1] & 0xFF;
            int length = readShort(bytes, index + 2);
            if (marker >= 0xC0 && marker <= 0xC3) {
                return new ImageDimensions(readShort(bytes, index + 7), readShort(bytes, index + 5));
            }
            index += 2 + Math.max(2, length);
        }
        return new ImageDimensions(0, 0);
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    private static int readShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static int readShortLittle(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }
}
