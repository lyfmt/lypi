package cn.lypi.session;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;

public final class GitWorkingTreeDiffQuery implements GitDiffQuery {
    private static final Charset UTF_8 = StandardCharsets.UTF_8;

    private final Path cwd;

    public GitWorkingTreeDiffQuery(Path cwd) {
        this.cwd = cwd;
    }

    @Override
    public List<GitDiffFileView> diff() {
        ProcessResult result = runGitStatus();
        if (result.exitCode() != 0) {
            return List.of();
        }
        return parseNullTerminatedPorcelain(result.stdout()).stream()
            .sorted(Comparator.comparing(view -> view.path().toString()))
            .toList();
    }

    /**
     * 返回当前 Git working tree 的完整 diff 视图。
     */
    public Optional<DiffView> diffView(int maxPatchBytes) {
        ProcessResult statusResult = runGitStatus();
        if (statusResult.exitCode() != 0) {
            return Optional.empty();
        }
        List<GitDiffFileView> files = parseNullTerminatedPorcelain(statusResult.stdout()).stream()
            .sorted(Comparator.comparing(view -> view.path().toString()))
            .toList();
        if (files.isEmpty()) {
            return Optional.empty();
        }

        int budget = Math.max(0, maxPatchBytes);
        Path gitRoot = gitRoot().orElse(cwd.toAbsolutePath().normalize());
        PatchResult patchResult = combinedPatch(files, gitRoot, budget);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("snapshotHash", snapshotHash(files, patchResult.patch()));
        metadata.put("fileCount", files.size());
        metadata.put("patchBytes", patchResult.patchBytes());
        return Optional.of(new DiffView(
            summary(files),
            files,
            patchResult.patch(),
            patchResult.truncated(),
            metadata
        ));
    }

    static GitDiffFileView parsePorcelainLine(String line) {
        if (line.length() < 4) {
            return null;
        }
        String code = line.substring(0, 2);
        String rawPath = line.substring(3);
        GitDiffStatus status = status(code);
        if (status == null) {
            return null;
        }
        Path path = Path.of(path(rawPath, status));
        return new GitDiffFileView(path, status, summary(status), Map.of("porcelain", line));
    }

    private static List<GitDiffFileView> parseNullTerminatedPorcelain(String stdout) {
        String[] tokens = stdout.split("\0", -1);
        List<GitDiffFileView> views = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isBlank()) {
                continue;
            }
            GitDiffFileView view = parsePorcelainToken(token);
            if (view == null) {
                continue;
            }
            views.add(view);
            if (view.status() == GitDiffStatus.RENAMED || view.status() == GitDiffStatus.COPIED) {
                i++;
            }
        }
        return views;
    }

    private static GitDiffFileView parsePorcelainToken(String token) {
        if (token.length() < 4) {
            return null;
        }
        String code = token.substring(0, 2);
        GitDiffStatus status = status(code);
        if (status == null) {
            return null;
        }
        String path = token.substring(3);
        return new GitDiffFileView(Path.of(path), status, summary(status), Map.of("porcelain", token));
    }

    private static GitDiffStatus status(String code) {
        char index = code.charAt(0);
        char workingTree = code.charAt(1);
        if (code.equals("??")) {
            return GitDiffStatus.UNTRACKED;
        }
        if (index == 'R' || workingTree == 'R') {
            return GitDiffStatus.RENAMED;
        }
        if (index == 'C' || workingTree == 'C') {
            return GitDiffStatus.COPIED;
        }
        if (index == 'A' || workingTree == 'A') {
            return GitDiffStatus.ADDED;
        }
        if (index == 'D' || workingTree == 'D') {
            return GitDiffStatus.DELETED;
        }
        if (index == 'M' || workingTree == 'M') {
            return GitDiffStatus.MODIFIED;
        }
        return null;
    }

    private static String path(String rawPath, GitDiffStatus status) {
        if (status != GitDiffStatus.RENAMED && status != GitDiffStatus.COPIED) {
            return rawPath;
        }
        int arrow = rawPath.indexOf(" -> ");
        return arrow >= 0 ? rawPath.substring(arrow + 4) : rawPath;
    }

    private static String summary(GitDiffStatus status) {
        return switch (status) {
            case MODIFIED -> "Modified";
            case ADDED -> "Added";
            case DELETED -> "Deleted";
            case RENAMED -> "Renamed";
            case COPIED -> "Copied";
            case UNTRACKED -> "Untracked";
        };
    }

    private ProcessResult runGitStatus() {
        return runGit("status", "--porcelain", "-z", "--renames", "--untracked-files=all");
    }

    private Optional<Path> gitRoot() {
        ProcessResult result = runGit("rev-parse", "--show-toplevel");
        if (result.exitCode() != 0 || result.stdout().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(result.stdout().strip()).toAbsolutePath().normalize());
    }

    private PatchResult combinedPatch(List<GitDiffFileView> files, Path gitRoot, int maxPatchBytes) {
        PatchAccumulator patch = new PatchAccumulator(maxPatchBytes);
        appendPatch(patch, runGit(gitRoot, patch.probeBytes(), "diff", "--cached", "--no-ext-diff", "--"));
        appendPatch(patch, runGit(gitRoot, patch.probeBytes(), "diff", "--no-ext-diff", "--"));
        for (GitDiffFileView file : files) {
            if (patch.truncated()) {
                break;
            }
            if (file.status() == GitDiffStatus.UNTRACKED) {
                appendPatch(patch, untrackedPatch(file.path(), gitRoot, patch.probeBytes()));
            }
        }
        return patch.result();
    }

    private ProcessResult untrackedPatch(Path path, Path gitRoot, int maxStdoutBytes) {
        Path resolved = gitRoot.resolve(path).normalize();
        if (!resolved.startsWith(gitRoot) || !Files.exists(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return new ProcessResult(1, "", "");
        }
        return runGit(gitRoot, maxStdoutBytes, "diff", "--no-ext-diff", "--no-index", "--", "/dev/null", path.toString());
    }

    private void appendPatch(PatchAccumulator patch, ProcessResult result) {
        if (result.exitCode() != 0 && result.exitCode() != 1 && !result.truncated()) {
            return;
        }
        if (result.stdout().isBlank()) {
            if (result.truncated()) {
                patch.markTruncated();
            }
            return;
        }
        patch.append(result.stdout().stripTrailing(), result.truncated());
    }

    private ProcessResult runGit(String... args) {
        return runGit(cwd, -1, args);
    }

    private ProcessResult runGit(Path workingDirectory, int maxStdoutBytes, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(workingDirectory.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        try {
            Process process = builder.start();
            ReadResult stdout = readStdout(process, maxStdoutBytes);
            String stderr = readStderr(process, stdout.truncated());
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, new String(stdout.bytes(), UTF_8), stderr, stdout.truncated());
        } catch (IOException e) {
            return new ProcessResult(1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(1, "", e.getMessage());
        }
    }

    private String readStderr(Process process, boolean allowClosedStream) throws IOException {
        try {
            return new String(process.getErrorStream().readAllBytes(), UTF_8);
        } catch (IOException exception) {
            if (allowClosedStream) {
                return "";
            }
            throw exception;
        }
    }

    private ReadResult readStdout(Process process, int maxBytes) throws IOException {
        InputStream input = process.getInputStream();
        if (maxBytes < 0) {
            return new ReadResult(input.readAllBytes(), false);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(0, maxBytes));
        byte[] buffer = new byte[8192];
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                return new ReadResult(output.toByteArray(), false);
            }
            int remaining = maxBytes - output.size();
            if (remaining > 0) {
                output.write(buffer, 0, Math.min(read, remaining));
            }
            if (read > remaining || output.size() >= maxBytes) {
                process.destroyForcibly();
                return new ReadResult(output.toByteArray(), true);
            }
        }
    }

    private static String summary(List<GitDiffFileView> files) {
        return files.size() == 1 ? "1 file changed" : files.size() + " files changed";
    }

    private static String truncateUtf8(String value, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        byte[] bytes = value.getBytes(UTF_8);
        if (bytes.length <= maxBytes) {
            return value;
        }
        String truncated = new String(bytes, 0, maxBytes, UTF_8);
        while (!truncated.isEmpty() && truncated.getBytes(UTF_8).length > maxBytes) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated;
    }

    private String snapshotHash(List<GitDiffFileView> files, String patch) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (GitDiffFileView file : files) {
                digest.update(file.path().toString().getBytes(UTF_8));
                digest.update((byte) 0);
                digest.update(file.status().name().getBytes(UTF_8));
                digest.update((byte) 0);
                Object porcelain = file.metadata().get("porcelain");
                if (porcelain != null) {
                    digest.update(porcelain.toString().getBytes(UTF_8));
                }
                digest.update((byte) 0);
            }
            digest.update(patch.getBytes(UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 digest is not available", exception);
        }
    }

    private static final class PatchAccumulator {
        private final int maxBytes;
        private final StringBuilder patch = new StringBuilder();
        private int patchBytes;
        private boolean truncated;

        private PatchAccumulator(int maxBytes) {
            this.maxBytes = Math.max(0, maxBytes);
        }

        private int probeBytes() {
            return Math.max(1, maxBytes - patchBytes + 1);
        }

        private void append(String section, boolean sourceTruncated) {
            if (section == null || section.isBlank()) {
                if (sourceTruncated) {
                    truncated = true;
                }
                return;
            }
            if (!patch.isEmpty()) {
                appendText("\n");
            }
            appendText(section);
            if (sourceTruncated) {
                truncated = true;
            }
        }

        private void appendText(String value) {
            int remaining = maxBytes - patchBytes;
            if (remaining <= 0) {
                truncated = true;
                return;
            }
            String appended = truncateUtf8(value, remaining);
            patch.append(appended);
            patchBytes += appended.getBytes(UTF_8).length;
            if (appended.length() < value.length()) {
                truncated = true;
            }
        }

        private void markTruncated() {
            truncated = true;
        }

        private boolean truncated() {
            return truncated;
        }

        private PatchResult result() {
            return new PatchResult(patch.toString(), patchBytes, truncated || patchBytes >= maxBytes);
        }
    }

    private record PatchResult(String patch, int patchBytes, boolean truncated) {}

    private record ReadResult(byte[] bytes, boolean truncated) {}

    private record ProcessResult(int exitCode, String stdout, String stderr, boolean truncated) {
        private ProcessResult(int exitCode, String stdout, String stderr) {
            this(exitCode, stdout, stderr, false);
        }
    }
}
