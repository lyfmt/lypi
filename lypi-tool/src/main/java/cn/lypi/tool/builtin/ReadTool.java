package cn.lypi.tool.builtin;

import cn.lypi.contracts.common.JsonSchema;
import cn.lypi.contracts.common.ProgressSink;
import cn.lypi.contracts.common.ToolProgress;
import cn.lypi.contracts.common.ValidationResult;
import cn.lypi.contracts.context.AttachmentContentBlock;
import cn.lypi.contracts.tool.ToolResult;
import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ReadTool extends AbstractFileTool {
    @Override
    public String name() {
        return "read";
    }

    @Override
    public JsonSchema inputSchema() {
        return new JsonSchema(Map.of(
            "type", "object",
            "required", List.of("path"),
            "properties", Map.of(
                "path", Map.of("type", "string"),
                "offset", Map.of("type", "integer", "minimum", 1),
                "limit", Map.of("type", "integer", "minimum", 1)
            )
        ));
    }

    @Override
    public ValidationResult validateInput(Map<String, Object> input, ToolUseContext context) {
        if (input.get("path") == null || input.get("path").toString().isBlank()) {
            return new ValidationResult(false, List.of("path 不能为空。"));
        }
        return new ValidationResult(true, List.of());
    }

    @Override
    public ToolResult<String> execute(Map<String, Object> input, ToolUseContext context, ProgressSink progress) {
        String toolUseId = toolUseId(context);
        try {
            Path path = resolvePath(input, context, "path");
            if (!Files.exists(path)) {
                return error(toolUseId, "文件不存在: " + relativePath(path, context));
            }
            if (Files.isDirectory(path)) {
                return error(toolUseId, "不能读取目录: " + relativePath(path, context));
            }
            progress.progress(ToolProgress.phase("reading", "读取文件"));
            byte[] bytes = Files.readAllBytes(path);
            String fileName = path.getFileName().toString();
            Optional<String> mediaType = ImageFileDetector.detect(bytes, fileName);
            if (mediaType.isPresent()) {
                return imageSuccess(toolUseId, fileName, bytes, mediaType.get());
            }
            if (BinaryFileDetector.isUnsupportedBinary(fileName, bytes)) {
                return error(toolUseId, "不能读取二进制文件。");
            }
            List<String> lines = List.of(new String(bytes, StandardCharsets.UTF_8).split("\\R", -1));
            if (!lines.isEmpty() && lines.getLast().isEmpty()) {
                lines = lines.subList(0, lines.size() - 1);
            }
            int offset = intInput(input, "offset", 1, 1, Math.max(1, lines.size()));
            int limit = intInput(input, "limit", lines.size(), 1, Math.max(1, lines.size()));
            int startIndex = offset - 1;
            int endIndex = Math.min(lines.size(), startIndex + limit);
            List<String> rendered = new ArrayList<>();
            rendered.add("File: " + relativePath(path, context));
            for (int index = startIndex; index < endIndex; index++) {
                rendered.add((index + 1) + " | " + lines.get(index));
            }
            progress.progress(new ToolProgress(
                cn.lypi.contracts.common.ToolProgressKind.STATUS,
                "read lines",
                relativePath(path, context),
                null,
                null,
                null,
                (long) (endIndex - startIndex),
                (long) lines.size(),
                null,
                Map.of()
            ));
            return success(toolUseId, String.join("\n", rendered));
        } catch (IllegalArgumentException exception) {
            return error(toolUseId, exception.getMessage());
        } catch (IOException exception) {
            return error(toolUseId, "读取文件失败: " + exception.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Map<String, Object> input) {
        return true;
    }

    @Override
    public boolean isDestructive(Map<String, Object> input) {
        return false;
    }

    private ToolResult<String> imageSuccess(String toolUseId, String fileName, byte[] bytes, String mediaType) {
        ImageReadProcessor.Result result = ImageReadProcessor.process(bytes, mediaType);
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(result.metadata());
        metadata.put("source", "read");
        metadata.put("toolUseId", toolUseId);
        metadata.put("imageUrl", result.imageUrl());
        metadata.put("detail", "high");
        metadata.put("fileName", fileName);
        metadata.put("sizeBytes", result.sizeBytes());
        String text = "Read image file [" + mediaType + "]";
        if (Boolean.TRUE.equals(result.metadata().get("resized"))) {
            Object width = result.metadata().get("displayWidth");
            Object height = result.metadata().get("displayHeight");
            if (width != null && height != null) {
                text += " resized to " + width + "x" + height;
            }
        }
        AttachmentContentBlock attachment = new AttachmentContentBlock(
            "read-image-" + toolUseId,
            "Image: " + mediaType,
            mediaType,
            Map.copyOf(metadata)
        );
        return ToolMessages.success(toolUseId, text, List.of(attachment));
    }

    @Override
    public String renderForUser(Map<String, Object> input) {
        return "read " + input;
    }
}
