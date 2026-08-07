package cn.john.dh.assistant.rag.spiltter;

import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 基于正则表达式的文本分词器
 * <p>
 * 仿照 LangChain4j 的 DocumentByRegexSplitter 实现：
 * 1. 按用户提供的正则表达式将文本分割为多个片段
 * 2. 将小片段聚合到 chunkSize 大小的块中，超出时保存当前块并开启新块
 * 3. 若单个片段超过 chunkSize，则按字符进行硬切分
 * 4. 相邻块之间保留 chunkOverlap 个字符的重叠
 *
 * @Author John
 * @Date 2026-08-05 23:26
 */
public class RegexTextSplitter extends TextSplitter {

    /**
     * 正则表达式（用于分割文本）
     */
    private final String regex;

    /**
     * 预编译的正则 Pattern，避免重复编译提升性能
     */
    private final Pattern pattern;

    /**
     * 每块最大字符数
     */
    private final int chunkSize;

    /**
     * 相邻块之间重叠字符数
     */
    private final int chunkOverlap;

    /**
     * 片段拼接时的分隔符（默认空字符串，直接拼接）
     */
    private final String joinDelimiter;

    public RegexTextSplitter(String regex, int chunkSize, int chunkOverlap) {
        this(regex, chunkSize, chunkOverlap, "");
    }

    public RegexTextSplitter(String regex, int chunkSize, int chunkOverlap, String joinDelimiter) {
        if (regex == null || regex.isBlank()) {
            throw new IllegalArgumentException("regex 不能为空");
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize 必须大于 0");
        }
        if (chunkOverlap < 0) {
            throw new IllegalArgumentException("chunkOverlap 不能为负数");
        }
        if (chunkOverlap >= chunkSize) {
            throw new IllegalArgumentException("chunkOverlap 不能大于等于 chunkSize");
        }
        this.regex = regex;
        this.pattern = Pattern.compile(regex);
        this.chunkSize = chunkSize;
        this.chunkOverlap = chunkOverlap;
        this.joinDelimiter = joinDelimiter == null ? "" : joinDelimiter;
    }

    /**
     * 分段
     *
     * @param text 文本
     * @return 分段后的文本列表
     */
    @Override
    protected List<String> splitText(String text) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        // 按正则表达式分割文本
        String[] segments = pattern.split(text);

        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String segment : segments) {
            if (segment.isEmpty()) {
                continue;
            }

            // 单个片段超过 chunkSize，先保存当前块，再按字符硬切分
            if (segment.length() > chunkSize) {
                // 保存当前已累积的块
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk.setLength(0);
                }
                // 按字符硬切分过长的片段（带重叠）
                int start = 0;
                while (start < segment.length()) {
                    int end = Math.min(start + chunkSize, segment.length());
                    chunks.add(segment.substring(start, end));
                    if (chunkOverlap > 0 && end < segment.length()) {
                        start = end - chunkOverlap;
                    } else {
                        start = end;
                    }
                }
                continue;
            }

            // 添加当前片段会超出 chunkSize，保存当前块并开启新块
            if (currentChunk.length() > 0
                    && currentChunk.length() + joinDelimiter.length() + segment.length() > chunkSize) {
                String chunkText = currentChunk.toString();
                chunks.add(chunkText);
                // 计算重叠部分作为新块的起始
                currentChunk.setLength(0);
                if (chunkOverlap > 0) {
                    int overlapStart = Math.max(0, chunkText.length() - chunkOverlap);
                    currentChunk.append(chunkText, overlapStart, chunkText.length());
                }
            }

            // 拼接分隔符（非首个片段时）
            if (currentChunk.length() > 0 && !joinDelimiter.isEmpty()) {
                currentChunk.append(joinDelimiter);
            }
            currentChunk.append(segment);
        }

        // 保存最后一块
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }
}
