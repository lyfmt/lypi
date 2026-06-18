package cn.lypi.tool.builtin;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

final class ImageReadProcessor {
    static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;
    static final int MAX_DIMENSION = 2000;
    static final int TARGET_IMAGE_BYTES = 1_500_000;

    private ImageReadProcessor() {
    }

    static Result process(byte[] bytes, String mediaType) {
        if (bytes.length == 0) {
            throw new IllegalArgumentException("图片文件为空。");
        }
        if (bytes.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("图片文件过大，最大支持 5MB。");
        }

        ImageDimensions originalDimensions = readDimensions(bytes, mediaType);
        byte[] outputBytes = bytes;
        boolean resized = false;
        ImageDimensions displayDimensions = originalDimensions;
        if (supportsResize(mediaType) && shouldResize(bytes, originalDimensions)) {
            ResizeResult resizeResult = resize(bytes, mediaType, originalDimensions);
            outputBytes = resizeResult.bytes();
            displayDimensions = resizeResult.dimensions();
            resized = resizeResult.resized();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (originalDimensions != null) {
            metadata.put("originalWidth", originalDimensions.width());
            metadata.put("originalHeight", originalDimensions.height());
        }
        if (displayDimensions != null) {
            metadata.put("displayWidth", displayDimensions.width());
            metadata.put("displayHeight", displayDimensions.height());
        }
        metadata.put("resized", resized);
        return new Result(
            "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(outputBytes),
            mediaType,
            outputBytes.length,
            Map.copyOf(metadata)
        );
    }

    private static boolean supportsResize(String mediaType) {
        return "image/png".equals(mediaType) || "image/jpeg".equals(mediaType);
    }

    private static boolean shouldResize(byte[] bytes, ImageDimensions dimensions) {
        if (bytes.length > TARGET_IMAGE_BYTES) {
            return true;
        }
        return dimensions != null && (dimensions.width() > MAX_DIMENSION || dimensions.height() > MAX_DIMENSION);
    }

    private static ImageDimensions readDimensions(byte[] bytes, String mediaType) {
        if (!supportsResize(mediaType)) {
            return null;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            return new ImageDimensions(image.getWidth(), image.getHeight());
        } catch (IOException exception) {
            return null;
        }
    }

    private static ResizeResult resize(byte[] bytes, String mediaType, ImageDimensions dimensions) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(bytes));
            if (source == null || dimensions == null) {
                return new ResizeResult(bytes, dimensions, false);
            }
            double scale = Math.min(1.0, (double) MAX_DIMENSION / Math.max(dimensions.width(), dimensions.height()));
            int width = Math.max(1, (int) Math.round(dimensions.width() * scale));
            int height = Math.max(1, (int) Math.round(dimensions.height() * scale));
            BufferedImage target = new BufferedImage(width, height, outputImageType(mediaType, source));
            Graphics2D graphics = target.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            return new ResizeResult(writeImage(target, mediaType), new ImageDimensions(width, height), true);
        } catch (IOException exception) {
            return new ResizeResult(bytes, dimensions, false);
        }
    }

    private static int outputImageType(String mediaType, BufferedImage source) {
        if ("image/jpeg".equals(mediaType)) {
            return BufferedImage.TYPE_INT_RGB;
        }
        return source.getType() == BufferedImage.TYPE_CUSTOM ? BufferedImage.TYPE_INT_ARGB : source.getType();
    }

    private static byte[] writeImage(BufferedImage image, String mediaType) throws IOException {
        if ("image/jpeg".equals(mediaType)) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                writer.setOutput(imageOutput);
                ImageWriteParam parameter = writer.getDefaultWriteParam();
                parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                parameter.setCompressionQuality(0.85F);
                writer.write(null, new IIOImage(image, null, null), parameter);
            } finally {
                writer.dispose();
            }
            return output.toByteArray();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    record Result(String imageUrl, String mediaType, int sizeBytes, Map<String, Object> metadata) {
    }

    private record ImageDimensions(int width, int height) {
    }

    private record ResizeResult(byte[] bytes, ImageDimensions dimensions, boolean resized) {
    }
}
