package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.constant.MetadataKeyConstant;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.domain.enums.SegmentStatus;
import com.alibaba.fastjson2.JSON;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 文档摄入服务
 * 支持将文档摄入到指定的知识库（Collection）中
 *
 * @Author John
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** DashScope Embedding API 单次最多支持 10 条文本 */
    private static final int EMBEDDING_BATCH_SIZE = 10;

    private final VectorStoreRouter vectorStoreRouter;
    private final TokenTextSplitter textSplitter;
    private final KnowledgeSegmentService knowledgeSegmentService;


    public DocumentIngestionService(VectorStoreRouter vectorStoreRouter, KnowledgeSegmentService knowledgeSegmentService) {
        this.vectorStoreRouter = vectorStoreRouter;
        this.knowledgeSegmentService = knowledgeSegmentService;
        this.textSplitter = new TokenTextSplitter(800, 350, 5, 10000, true);
    }

    /**
     * 从资源文件摄入文档到指定知识库
     *
     * @param resource      文件资源
     * @param metadata      附加元数据
     * @param knowledgeBase 目标知识库
     * @return 摄入的文档块数量
     */
    public int ingest(Resource resource, Map<String, Object> metadata, KnowledgeBase knowledgeBase) {
        log.info("开始摄入文档到 [{}]: {}", knowledgeBase.getDescription(), resource.getFilename());

        TikaDocumentReader reader = new TikaDocumentReader(resource);
        List<Document> documents = reader.get();

        documents.forEach(doc -> {
            doc.getMetadata().putAll(metadata);
            doc.getMetadata().put("knowledgeBase", knowledgeBase.name());
            if (!doc.getMetadata().containsKey("source")) {
                doc.getMetadata().put("source", resource.getFilename());
            }
        });

        List<Document> chunks = textSplitter.apply(documents);
        VectorStore store = vectorStoreRouter.route(knowledgeBase);
        addInBatches(store, chunks);

        log.info("文档 '{}' 已摄入 [{}]，共 {} 个块", resource.getFilename(), knowledgeBase.getDescription(), chunks.size());
        return chunks.size();
    }

    /**
     * 摄入到默认知识库
     */
    public int ingest(Resource resource, Map<String, Object> metadata) {
        return ingest(resource, metadata, KnowledgeBase.GENERAL);
    }

    public List<String> ingest(List<KnowledgeSegment> segments, KnowledgeBase knowledgeBase) {
        if (CollectionUtils.isEmpty(segments)) {
            return Collections.emptyList();
        }
        // 2. 为每个分段构建 Document，用 chunkId 作为 Document.id
        List<Document> documents = new ArrayList<>();
        for (KnowledgeSegment segment : segments) {
            if (segment.getSkipEmbedding() == 1) continue;  // 跳过父分片
            Document doc = Document.builder()
                    .text(segment.getText())
                    .metadata(JSON.parseObject(segment.getMetadata(), Map.class))
                    .id(segment.getChunkId())              // ← chunkId 即为 embeddingId
                    .build();
            // 添加文档
            documents.add(doc);
        }
        //存入 Milvus（Document.id 就是 Milvus 主键）
        MilvusVectorStore store = vectorStoreRouter.route(knowledgeBase);
        addInBatches(store, documents);
        //embeddingId 就是 chunkId，回写到分段表
        for (KnowledgeSegment segment : segments) {
            if (segment.getSkipEmbedding() == 1) continue;
            segment.setEmbeddingId(segment.getChunkId());  // ← 同一个 ID
            segment.setStatus(SegmentStatus.VECTOR_STORED);
        }
        knowledgeSegmentService.updateBatchById(segments);
        return documents.stream().map(Document::getId).toList();
    }

    /**
     * 重新嵌入单个分段（分段文本编辑后同步向量库）
     * <p>
     * 先按 chunkId 删除旧向量，再重新生成向量写入，并回写 embeddingId 与状态。
     *
     * @param segment       待重新嵌入的分段（text 已是最新内容）
     * @param knowledgeBase 分段所属知识库
     */
    public void reEmbedSegment(KnowledgeSegment segment, KnowledgeBase knowledgeBase) {
        // 1. 删除旧向量
        String filter = "metadata[\"" + MetadataKeyConstant.CHUNK_ID + "\"] == \"" + segment.getChunkId() + "\"";
        deleteByFilter(filter, knowledgeBase);
        // 2. 重新嵌入并回写 embeddingId / 状态
        ingest(List.of(segment), knowledgeBase);
        log.info("分段向量已更新, segmentId={}, chunkId={}", segment.getId(), segment.getChunkId());
    }

    /**
     * 直接摄入纯文本到指定知识库
     */
    public int ingestText(String content, Map<String, Object> metadata, KnowledgeBase knowledgeBase) {
        Document document = new Document(content, metadata);
        document.getMetadata().put("knowledgeBase", knowledgeBase.name());
        List<Document> chunks = textSplitter.apply(List.of(document));
        VectorStore store = vectorStoreRouter.route(knowledgeBase);
        addInBatches(store, chunks);
        log.info("文本已摄入 [{}]，共 {} 个块", knowledgeBase.getDescription(), chunks.size());
        return chunks.size();
    }

    /**
     * 摄入纯文本到默认知识库
     */
    public int ingestText(String content, Map<String, Object> metadata) {
        return ingestText(content, metadata, KnowledgeBase.GENERAL);
    }

    /**
     * 分批写入向量存储，规避 DashScope Embedding API 单次 10 条文本的限制
     *
     * @param store     向量存储
     * @param documents 文档列表
     */
    private void addInBatches(VectorStore store, List<Document> documents) {
        if (CollectionUtils.isEmpty(documents)) {
            return;
        }
        for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, documents.size());
            store.add(documents.subList(i, end));
        }
    }

    // ======================== 向量删除 ========================

    /**
     * 删除指定文档的所有向量
     * <p>
     * 通过 Milvus 原生 filter 表达式直接删除，无需先查询 chunkIds。
     *
     * @param docId          文档ID（knowledge_document.doc_id）
     * @param knowledgeBase  文档所属知识库（决定路由到哪个 Collection）
     */
    public void deleteByDocId(Long docId, KnowledgeBase knowledgeBase) {
        String filter = "metadata[\"" + MetadataKeyConstant.DOC_ID + "\"] == " + docId;
        deleteByFilter(filter, knowledgeBase);
    }

    /**
     * 删除指定文档特定版本的所有向量
     * <p>
     * 通过 Milvus 原生 filter 表达式直接删除，无需先查询 chunkIds。
     *
     * @param docId          文档ID（knowledge_document.doc_id）
     * @param versionId      文档版本ID（knowledge_document_version.version_id）
     * @param knowledgeBase  文档所属知识库（决定路由到哪个 Collection）
     */
    public void deleteByDocIdAndVersionId(Long docId, Long versionId, KnowledgeBase knowledgeBase) {
        String filter = "metadata[\"" + MetadataKeyConstant.DOC_ID + "\"] == " + docId
                + " and metadata[\"" + MetadataKeyConstant.VERSION_ID + "\"] == " + versionId;
        deleteByFilter(filter, knowledgeBase);
    }

    /**
     * 通过 Milvus 原生 filter 表达式删除向量
     *
     * @param filterExpr    Milvus 过滤表达式（如 metadata["docId"] == 123）
     * @param knowledgeBase 目标知识库
     */
    private void deleteByFilter(String filterExpr, KnowledgeBase knowledgeBase) {
        MilvusVectorStore store = vectorStoreRouter.route(knowledgeBase);
        MilvusServiceClient client = store.<MilvusServiceClient>getNativeClient()
                .orElseThrow(() -> new IllegalStateException("无法获取 Milvus 原生客户端"));
        R<MutationResult> response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(knowledgeBase.getCollectionName())
                .withExpr(filterExpr)
                .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BusinessException("从 Milvus 删除向量失败: " + response.getMessage());
        }
        log.info("已从 [{}] 按 filter [{}] 删除 {} 条向量",
                knowledgeBase.getDescription(), filterExpr, response.getData().getDeleteCnt());
    }
}
