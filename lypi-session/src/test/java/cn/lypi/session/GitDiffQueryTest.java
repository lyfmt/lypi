package cn.lypi.session;

import static org.assertj.core.api.Assertions.assertThat;

import cn.lypi.contracts.tui.DiffView;
import cn.lypi.contracts.tui.GitDiffFileView;
import cn.lypi.contracts.tui.GitDiffStatus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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

    @Test
    void diffViewIncludesFilesPatchAndStableMetadata() throws Exception {
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@example.com");
        runGit(tempDir, "config", "user.name", "Test User");
        Files.writeString(tempDir.resolve("modified.txt"), "before\n");
        runGit(tempDir, "add", "modified.txt");
        runGit(tempDir, "commit", "-m", "initial");

        Files.writeString(tempDir.resolve("modified.txt"), "after\n");
        Files.writeString(tempDir.resolve("staged.txt"), "staged\n");
        runGit(tempDir, "add", "staged.txt");
        Files.writeString(tempDir.resolve("untracked.txt"), "untracked\n");

        Optional<DiffView> view = new GitWorkingTreeDiffQuery(tempDir).diffView(16_384);

        assertThat(view).isPresent();
        DiffView diffView = view.orElseThrow();
        assertThat(diffView.summary()).isEqualTo("3 files changed");
        assertThat(diffView.files()).extracting(GitDiffFileView::path)
            .containsExactly(Path.of("modified.txt"), Path.of("staged.txt"), Path.of("untracked.txt"));
        assertThat(diffView.patch())
            .contains("diff --git a/modified.txt b/modified.txt")
            .contains("+after")
            .contains("diff --git a/staged.txt b/staged.txt")
            .contains("diff --git a/untracked.txt b/untracked.txt");
        assertThat(diffView.truncated()).isFalse();
        assertThat(diffView.metadata())
            .containsKey("snapshotHash")
            .containsEntry("fileCount", 3);
    }

    @Test
    void diffViewIncludesUntrackedPatchWhenCwdIsRelative() throws Exception {
        runGit(tempDir, "init");
        Files.writeString(tempDir.resolve("untracked.txt"), "untracked\n");
        Path relativeCwd = Path.of("").toAbsolutePath().normalize().relativize(tempDir.toAbsolutePath().normalize());

        DiffView view = new GitWorkingTreeDiffQuery(relativeCwd).diffView(16_384).orElseThrow();

        assertThat(view.files()).extracting(GitDiffFileView::path).containsExactly(Path.of("untracked.txt"));
        assertThat(view.patch()).contains("diff --git a/untracked.txt b/untracked.txt");
    }

    @Test
    void diffViewExpandsUntrackedDirectoriesIntoFiles() throws Exception {
        runGit(tempDir, "init");
        Files.createDirectories(tempDir.resolve("dir"));
        Files.writeString(tempDir.resolve("dir/a.txt"), "a\n");
        Files.writeString(tempDir.resolve("dir/b.txt"), "b\n");

        DiffView view = new GitWorkingTreeDiffQuery(tempDir).diffView(16_384).orElseThrow();

        assertThat(view.files()).extracting(GitDiffFileView::path)
            .containsExactly(Path.of("dir/a.txt"), Path.of("dir/b.txt"));
        assertThat(view.patch())
            .contains("diff --git a/dir/a.txt b/dir/a.txt")
            .contains("diff --git a/dir/b.txt b/dir/b.txt");
    }

    @Test
    void diffViewIncludesUntrackedFilesWhenGitConfigHidesThem() throws Exception {
        runGit(tempDir, "init");
        runGit(tempDir, "config", "status.showUntrackedFiles", "no");
        Files.writeString(tempDir.resolve("untracked.txt"), "untracked\n");

        DiffView view = new GitWorkingTreeDiffQuery(tempDir).diffView(16_384).orElseThrow();

        assertThat(view.files()).extracting(GitDiffFileView::path).containsExactly(Path.of("untracked.txt"));
        assertThat(view.patch()).contains("diff --git a/untracked.txt b/untracked.txt");
    }

    @Test
    void diffViewResolvesUntrackedPatchFromRepositoryRootWhenCwdIsSubdirectory() throws Exception {
        runGit(tempDir, "init");
        Files.createDirectories(tempDir.resolve("sub"));
        Files.writeString(tempDir.resolve("sub/new.txt"), "new\n");

        DiffView view = new GitWorkingTreeDiffQuery(tempDir.resolve("sub")).diffView(16_384).orElseThrow();

        assertThat(view.files()).extracting(GitDiffFileView::path).containsExactly(Path.of("sub/new.txt"));
        assertThat(view.patch()).contains("diff --git a/sub/new.txt b/sub/new.txt");
    }

    @Test
    void diffViewIncludesBrokenUntrackedSymlinkPatch() throws Exception {
        runGit(tempDir, "init");
        Files.createSymbolicLink(tempDir.resolve("broken-link"), Path.of("missing-target"));

        DiffView view = new GitWorkingTreeDiffQuery(tempDir).diffView(16_384).orElseThrow();

        assertThat(view.files()).extracting(GitDiffFileView::path).containsExactly(Path.of("broken-link"));
        assertThat(view.patch())
            .contains("diff --git a/broken-link b/broken-link")
            .contains("new file mode 120000")
            .contains("+missing-target");
    }

    @Test
    void diffViewTruncatesPatchByByteBudget() throws Exception {
        runGit(tempDir, "init");
        runGit(tempDir, "config", "user.email", "test@example.com");
        runGit(tempDir, "config", "user.name", "Test User");
        Files.writeString(tempDir.resolve("large.txt"), "before\n");
        runGit(tempDir, "add", "large.txt");
        runGit(tempDir, "commit", "-m", "initial");
        Files.writeString(tempDir.resolve("large.txt"), "after\n".repeat(200));

        DiffView view = new GitWorkingTreeDiffQuery(tempDir).diffView(80).orElseThrow();

        assertThat(view.truncated()).isTrue();
        assertThat(view.patch().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(80);
        assertThat((Integer) view.metadata().get("patchBytes")).isLessThanOrEqualTo(81);
        assertThat(view.metadata()).containsKey("snapshotHash");
    }

    @Test
    void diffViewReturnsEmptyOutsideGitWorkspace() {
        assertThat(new GitWorkingTreeDiffQuery(tempDir).diffView(16_384)).isEmpty();
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
