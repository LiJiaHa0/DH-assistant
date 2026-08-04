package cn.john.dh.assistant.rag.config;

import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 向量存储路由器 —— 根据 KnowledgeBase 枚举路由到对应的 MilvusVectorStore
 * 类似于 MyBatis 中 SqlSession 根据 Mapper 接口选择对应的表操作
 *
 * @Author John
 */
@Component
public class VectorStoreRouter {

    private final Map<KnowledgeBase, MilvusVectorStore> storeMap = new EnumMap<>(KnowledgeBase.class);

    public VectorStoreRouter(
            @Qualifier("generalVectorStore") MilvusVectorStore generalVectorStore,
            @Qualifier("productVectorStore") MilvusVectorStore productVectorStore,
            @Qualifier("techVectorStore") MilvusVectorStore techVectorStore
    ) {
        storeMap.put(KnowledgeBase.GENERAL, generalVectorStore);
        storeMap.put(KnowledgeBase.PRODUCT, productVectorStore);
        storeMap.put(KnowledgeBase.TECH, techVectorStore);
    }

    /**
     * 根据知识库类型获取对应的 VectorStore
     *
     * @param knowledgeBase 知识库类型
     * @return 对应的 MilvusVectorStore 实例
     */
    public MilvusVectorStore route(KnowledgeBase knowledgeBase) {
        MilvusVectorStore store = storeMap.get(knowledgeBase);
        if (store == null) {
            throw new IllegalArgumentException("未知的知识库类型: " + knowledgeBase);
        }
        return store;
    }

    /**
     * 获取默认知识库（GENERAL）
     */
    public MilvusVectorStore getDefault() {
        return storeMap.get(KnowledgeBase.GENERAL);
    }
}
