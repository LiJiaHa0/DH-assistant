package cn.john.dh.assistant.rag.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * 多 Collection 向量存储配置
 * 为不同业务场景创建独立的 MilvusVectorStore 实例，各自对应不同的 collection
 *
 * @Author John
 */
@Configuration
public class VectorStoreConfig {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int port;

    /**
     * Milvus 客户端连接（所有 VectorStore 共享同一个连接）
     */
    @Bean
    public MilvusServiceClient milvusClient() {
        return new MilvusServiceClient(
                ConnectParam.newBuilder()
                        .withHost(host)
                        .withPort(port)
                        .build()
        );
    }

    /**
     * 通用知识文档库（默认）
     */
    @Bean
    @Primary
    public MilvusVectorStore generalVectorStore(MilvusServiceClient milvusClient, @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("dh_general_docs")
                .embeddingDimension(1024)
                .indexType(IndexType.IVF_FLAT)
                .metricType(MetricType.COSINE)
                .initializeSchema(true)
                .build();
    }

    /**
     * 产品/业务文档库
     */
    @Bean
    public MilvusVectorStore productVectorStore(MilvusServiceClient milvusClient, @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("dh_product_docs")
                .embeddingDimension(1024)
                .indexType(IndexType.IVF_FLAT)
                .metricType(MetricType.COSINE)
                .initializeSchema(true)
                .build();
    }

    /**
     * 技术/开发文档库
     */
    @Bean
    public MilvusVectorStore techVectorStore(MilvusServiceClient milvusClient, @Qualifier("dashscopeEmbeddingModel") EmbeddingModel embeddingModel) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("dh_tech_docs")
                .embeddingDimension(1024)
                .indexType(IndexType.IVF_FLAT)
                .metricType(MetricType.COSINE)
                .initializeSchema(true)
                .build();
    }
}
