package cn.lypi.tool.builtin;

import cn.lypi.contracts.tool.ToolUseContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

final class WorkspacePaths {
    private WorkspacePaths() {
    }

    static Path resolvePath(Map<String, Object> input, ToolUseContext context, String fieldName) {
        Object raw = input.get(fieldName);
        String value = raw == null ? "." : raw.toString();
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path resolved = cwd.resolve(value).normalize();
        if (!resolved.startsWith(cwd)) {
            throw new IllegalArgumentException("路径越过当前工作目录: " + value);
        }
        return resolved;
    }

    static String relativePath(Path path, ToolUseContext context) {
        Path cwd = context.cwd().toAbsolutePath().normalize();
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(cwd)) {
            return normalized.toString();
        }
        String relative = cwd.relativize(normalized).toString().replace('\\', '/');
        return relative.isBlank() ? "." : relative;
    }

    static Path requireRealPathInsideWorkspace(Path path, ToolUseContext context) throws IOException {
        Path realCwd = context.cwd().toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realCwd)) {
            throw new IllegalArgumentException("路径经符号链接越过当前工作目录: " + relativePath(path, context));
        }
        return realPath;
    }

    static boolean realPathInsideWorkspace(Path path, ToolUseContext context) {
        try {
            Path realCwd = context.cwd().toRealPath();
            Path realPath = path.toRealPath();
            return realPath.startsWith(realCwd);
        } catch (IOException exception) {
            return false;
        }
    }

    static void writeAtomically(Path path, String content) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            parent = Path.of(".");
        }
        Path temp = Files.createTempFile(parent, "." + path.getFileName(), ".tmp");
        try {
            Files.writeString(temp, content);
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            Files.deleteIfExists(temp);
            throw exception;
        }
    }
}
