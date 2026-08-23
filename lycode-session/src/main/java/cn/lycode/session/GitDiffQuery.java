package cn.lycode.session;

import cn.lycode.contracts.tui.GitDiffFileView;
import java.util.List;

public interface GitDiffQuery {
    /**
     * 返回当前 Git working tree 的文件级 diff 视图。
     */
    List<GitDiffFileView> diff();
}
