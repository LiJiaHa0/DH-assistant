package cn.john.dh.assistant.rag.domain.record;

import org.springframework.web.multipart.MultipartFile;

/**
 * @Author John
 * @Date 2026-07-31 10:49
 */
public record KnowledgeUploadParam(
        /**
         * 文件
         */
        MultipartFile file,

        /**
         * 标题
         */
        String title,
        /**
         * 描述
         */
        String description,

        /**
         * 知识库类型：DOCUMENT_SEARCH, DATA_QUERY
         */
        String knowledgeBaseType,

        /**
         * 知识库（向量库）：GENERAL, PRODUCT, TECH，对应 Milvus 中一个独立的 Collection
         */
        String docType,

        /**
         * 表名（仅 DATA_QUERY 时使用）
         */
        String tableName,

        /**
         * 版本
         */
        String version
) {
}
