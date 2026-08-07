package cn.john.dh.assistant.constant;

/**
 * @Author John
 * @Date 2026-08-05 16:57
 */
public class MetadataKeyConstant {
    /**
     * 文件名称
     */
    public static final String FILE_NAME = "fileName";


    public static final String DOC_ID = "docId";

    public static final String CHUNK_ID = "chunkId";

    public static final String EMBEDDING_ID = "EMBEDDING_ID";

    /**
     * 父块ID
     */
    public static final String PARENT_CHUNK_ID = "parentChunkId";

    /**
     * 同级块ID
     */
    public static final String BROTHER_CHUNK_ID = "brotherChunkId";


    public static final String BROTHER_CHUNK_INDEX = "brotherChunkIndex";

    public static final String BROTHER_CHUNK_TOTAL = "brotherChunkTotal";

    /**
     * 头级别
     */
    public static final String HEADER_LEVEL = "headerLevel";

    /**
     * 访问权限
     */
    public static final String ACCESSIBLE_BY = "accessibleBy";

    /**
     * 文件地址
     */
    public static final String URL = "url";

    /**
     * 文件版本
     */
    public static final String VERSION = "version";

    /**
     * 文档版本ID（knowledge_document_version.version_id）
     */
    public static final String VERSION_ID = "versionId";

    /**
     * 分类
     */
    public static final String CATEGORY = "category";

    /**
     * 摘要
     */
    public static final String SUMMARY = "summary";

    /**
     * 关键字
     */
    public static final String KEYWORDS = "keywords";

    /**
     * 跳过embedding标记，true表示不需要做embedding
     */
    public static final String SKIP_EMBEDDING = "skipEmbedding";

    /**
     * 跳过重排序/融合标记，true表示该内容来自结构化查询（SQL/Cypher），不需要参与重排序和融合
     */
    public static final String SKIP_RERANK = "skipRerank";

}
