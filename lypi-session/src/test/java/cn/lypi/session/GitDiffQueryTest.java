package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitDiffQueryTest {
    @TempDir
    Path tempDir;

    @Test
    void diffParsesGitPorcelainStatusForWorkingTreeFiles() throws Exception {
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@example.com");
        runGit(tempDir, "config", "user.name", "Test User");
        Files.writeString(tempDir.resolve("modified.txt"), "before\n");
        Files.writeString(tempDir.resolve("deleted.txt"), "deleted\n");
        Files.writeString(tempDir.resolve("renamed-old.txt"), "renamed\n");
        Files.writeString(tempDir.resolve("spaced old.txt"), "spaced\n");
        runGit(tempDir, "add", "modified.txt", "deleted.txt", "renamed-old.txt", "spaced old.txt");
        runGit(tempDir, "commit", "-m", "initial");

        Files.writeString(tempDir.resolve("modified.txt"), "after\n");
        Files.delete(tempDir.resolve("deleted.txt"));
        Files.move(tempDir.resolve("renamed-old.txt"), tempDir.resolve("renamed-new.txt"));
        Files.move(tempDir.resolve("spaced old.txt"), tempDir.resolve("spaced new.txt"));
        Files.writeString(tempDir.resolve("added.txt"), "added\n");
        runGit(
            tempDir,
            "add",
            "added.txt",
            "renamed-old.txt",
            "renamed-new.txt",
            "spaced old.txt",
            "spaced new.txt"
        );
        Files.writeString(tempDir.resolve("untracked file.txt"), "new\n");

        List<GitDiffFileView> files = new GitWorkingTreeDiffQuery(tempDir).diff();

        assertThat(files).extracting(GitDiffFileView::path)
            .containsExactly(
                Path.of("added.txt"),
                Path.of("deleted.txt"),
                Path.of("modified.txt"),
                Path.of("renamed-new.txt"),
                Path.of("spaced new.txt"),
                Path.of("untracked file.txt")
            );
        assertThat(files).extracting(GitDiffFileView::status)
            .containsExactly(
                GitDiffStatus.ADDED,
                GitDiffStatus.DELETED,
                GitDiffStatus.MODIFIED,
                GitDiffStatus.RENAMED,
                GitDiffStatus.RENAMED,
                GitDiffStatus.UNTRACKED
            );
        assertThat(files).allSatisfy(file -> assertThat(file.metadata()).containsKey("porcelain"));
    }

    @Test
    void parserSupportsCopiedPorcelainStatus() {
        GitDiffFileView copied = GitWorkingTreeDiffQuery.parsePorcelainLine("C  original.txt -> copy.txt");

        assertThat(copied.path()).isEqualTo(Path.of("copy.txt"));
        assertThat(copied.status()).isEqualTo(GitDiffStatus.COPIED);
    }

    @Test
    void diffReturnsEmptyResultOutsideGitWorkspace() {
        List<GitDiffFileView> files = new GitWorkingTreeDiffQuery(tempDir).diff();

        assertThat(files).isEmpty();
    }

    private static void runGit(Path cwd, String... args) throws Exception {
        ProcessBuilder builder = new ProcessBuilder();
        builder.command(command(args));
        builder.directory(cwd.toFile());
        Process process = builder.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError(new String(process.getErrorStream().readAllBytes()));
        }
    }

    private static List<String> command(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        return command;
    }
}
