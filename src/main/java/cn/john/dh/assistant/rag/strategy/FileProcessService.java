package cn.john.dh.assistant.rag.strategy;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.FileType;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;

import java.io.InputStream;

/**
 * 文件处理策略接口
 * @Author John
 * @Date 2026-07-31 17:52
 */
public interface FileProcessService {

    /**
     * 处理文件
     * @param document
     * @param inputStream
     * @return
     */
    String processDocument(KnowledgeDocument document, InputStream inputStream);

    /**
     * 判断是否支持该文件
     */
    boolean supports(FileType fileType, KnowledgeBaseType knowledgeBaseType);


}
