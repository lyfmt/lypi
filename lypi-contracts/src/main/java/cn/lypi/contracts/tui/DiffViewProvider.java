package cn.lypi.contracts.tui;

import java.nio.file.Path;
import java.util.Optional;

public interface DiffViewProvider {
    /**
     * 返回指定工作目录当前 Git diff 视图。
     *
     * NOTE: 返回空表示没有可展示的 diff，或工作目录不是 Git 仓库。
     */
    Optional<DiffView> currentDiff(Path cwd, int maxPatchBytes);
}
