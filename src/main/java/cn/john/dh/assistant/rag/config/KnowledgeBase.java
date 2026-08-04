package cn.john.dh.assistant.rag.config;

/**
 * 知识库类型枚举 —— 类似 MyBatis 中实体类与表的映射关系
 * 每个枚举值对应 Milvus 中一个独立的 Collection（向量表）
 *
 * 使用方式：在业务代码中通过 KnowledgeBase 指定要操作哪个向量表，
 * VectorStoreRouter 会根据枚举值路由到对应的 MilvusVectorStore 实例
 *
 * @Author John
 */
public enum KnowledgeBase {

    /** 通用知识文档（默认库） */
    GENERAL("dh_general_docs", "通用知识库"),

    /** 产品/业务文档 */
    PRODUCT("dh_product_docs", "产品文档库"),

    /** 技术/开发文档 */
    TECH("dh_tech_docs", "技术文档库");

    /** 对应 Milvus 中的 collection 名称 */
    private final String collectionName;

    /** 知识库描述 */
    private final String description;

    KnowledgeBase(String collectionName, String description) {
        this.collectionName = collectionName;
        this.description = description;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public String getDescription() {
        return description;
    }
}
