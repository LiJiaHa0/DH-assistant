package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

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

    private final VectorStoreRouter vectorStoreRouter;
    private final TokenTextSplitter textSplitter;

    public DocumentIngestionService(VectorStoreRouter vectorStoreRouter) {
        this.vectorStoreRouter = vectorStoreRouter;
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
        store.add(chunks);

        log.info("文档 '{}' 已摄入 [{}]，共 {} 个块", resource.getFilename(), knowledgeBase.getDescription(), chunks.size());
        return chunks.size();
    }

    /**
     * 摄入到默认知识库
     */
    public int ingest(Resource resource, Map<String, Object> metadata) {
        return ingest(resource, metadata, KnowledgeBase.GENERAL);
    }

    /**
     * 直接摄入纯文本到指定知识库
     */
    public int ingestText(String content, Map<String, Object> metadata, KnowledgeBase knowledgeBase) {
        Document document = new Document(content, metadata);
        document.getMetadata().put("knowledgeBase", knowledgeBase.name());
        List<Document> chunks = textSplitter.apply(List.of(document));
        VectorStore store = vectorStoreRouter.route(knowledgeBase);
        store.add(chunks);
        log.info("文本已摄入 [{}]，共 {} 个块", knowledgeBase.getDescription(), chunks.size());
        return chunks.size();
    }

    /**
     * 摄入纯文本到默认知识库
     */
    public int ingestText(String content, Map<String, Object> metadata) {
        return ingestText(content, metadata, KnowledgeBase.GENERAL);
    }
}
