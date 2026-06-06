package cn.lypi.session;

import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class GitWorkingTreeDiffQuery implements GitDiffQuery {
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
        ProcessBuilder builder = new ProcessBuilder(
            "git",
            "-C",
            cwd.toString(),
            "status",
            "--porcelain",
            "-z",
            "--renames"
        );
        try {
            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            return new ProcessResult(exitCode, stdout, stderr);
        } catch (IOException e) {
            return new ProcessResult(1, "", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(1, "", e.getMessage());
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {}
}
