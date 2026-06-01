package cn.lypi.contracts.common;

public interface ProgressSink {
    /*
    * @status : 未完成
    * @summary : 发布当前任务进度文本。
    *@description : 工具执行、外部进程和 MCP 调用可通过该接口向事件流报告进度。
    *
    *
                              */
    void progress(String message);
}

