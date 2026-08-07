package cn.john.dh.assistant.rag.domain.record;

/**
 * @Author John
 * @Date 2026-08-05 15:51
 */
public record DocumentSplitParam(
        // 分割类型
        String splitType,
        // 分块大小
        Integer chunkSize,
        // 重叠大小
        Integer overlap,
        // 重叠部分
        Integer titleLevel,
        // 标题级别
        String separator,
        // 正则表达式
        String regex) {
}
