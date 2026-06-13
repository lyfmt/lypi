package cn.lypi.resource;

import java.util.List;

/**
 * 追加 system prompt 的一个逻辑分区。
 */
interface SystemPromptSection {
    /**
     * 将当前分区追加到 prompt 内容和来源列表。
     *
     * NOTE: Section 只能消费构造时传入的数据，不直接读取文件系统。
     */
    void appendTo(StringBuilder content, List<String> sourceNames);
}
