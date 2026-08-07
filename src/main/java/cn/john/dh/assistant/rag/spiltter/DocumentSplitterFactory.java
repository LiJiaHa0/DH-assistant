package cn.john.dh.assistant.rag.spiltter;

import cn.john.dh.assistant.rag.domain.record.DocumentSplitParam;
import com.alibaba.cloud.ai.transformer.splitter.RecursiveCharacterTextSplitter;
import org.springframework.ai.transformer.splitter.TextSplitter;

/**
 * @Author John
 * @Date 2026-08-05 17:38
 */
public class DocumentSplitterFactory {


    public static TextSplitter getInstance(DocumentSplitParam param) {
        return switch (param.splitType()) {
            case "LENGTH" -> new OverlapParagraphTextSplitter(param.chunkSize(), param.overlap());
            case "TITLE" -> new MarkdownHeaderParentTextSplitter(param.titleLevel(), param.chunkSize(), param.overlap());
            case "SEPARATOR" -> new RecursiveCharacterTextSplitter(param.chunkSize(), new String[]{param.separator(), "\n\n", "\n"});
            case "REGEX" -> new RegexTextSplitter(param.regex(), param.chunkSize(), param.overlap());
            case "SMART" -> new MarkdownHeaderParentTextSplitter(param.titleLevel(),param.chunkSize(), (int) (param.chunkSize() * 0.1));
            default -> throw new IllegalArgumentException("不支持的切分方式: " + param.splitType());
        };
    }
}
