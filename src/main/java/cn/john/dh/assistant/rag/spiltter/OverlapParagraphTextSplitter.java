package cn.john.dh.assistant.rag.spiltter;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 带chunkOverlap的分段器
 *
 * @Author John
 * @Date 2026-08-05 23:19
 */
public class OverlapParagraphTextSplitter extends TextSplitter {
    // 每块最大字符数
    protected final int chunkSize;


    // 相邻块之间重叠字符数
    protected final int overlap;

    public OverlapParagraphTextSplitter(int chunkSize, int overlap) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (overlap < 0) {
            throw new IllegalArgumentException("overlap 不能为负数");
        }
        if (overlap >= chunkSize) {
            throw new IllegalArgumentException("overlap 不能大于等于 chunkSize");
        }
        this.chunkSize = chunkSize;
        this.overlap = overlap;
    }

    /**
     * 分段
     *
     * @param text 文本
     * @return 分段后的文本列表
     */
    @Override
    protected List<String> splitText(String text) {
        if (StringUtils.isBlank(text)) return Collections.emptyList();
        // 按段落分隔
        String[] paragraphs = text.split("\\n+");
        List<String> allChunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        // 遍历每个段落
        for (String paragraph : paragraphs) {
            if (StringUtils.isBlank(paragraph)) continue;
            // 按段落分隔
            int start = 0;
            // 遍历每个段落
            while (start < paragraph.length()) {
                // 计算剩余空间
                int remainingSpace = chunkSize - currentChunk.length();
                // 计算当前块的结束位置
                int end = Math.min(start + remainingSpace, paragraph.length());
                // 将当前段落添加到当前块
                currentChunk.append(paragraph, start, end);
                // 如果当前块已满，保存并生成新块
                if (currentChunk.length() >= chunkSize) {
                    allChunks.add(currentChunk.toString());
                    // 计算重叠
                    String overlapText = "";
                    if (overlap > 0) {
                        int overlapStart = Math.max(0, currentChunk.length() - overlap);
                        overlapText = currentChunk.substring(overlapStart);
                    }

                    currentChunk = new StringBuilder();
                    if (!overlapText.isEmpty())
                        currentChunk.append(overlapText);
                }

                start = end;
            }
        }
        if (currentChunk.length() > 0)
            allChunks.add(currentChunk.toString());
        return allChunks;
    }
}
