package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.config.VectorStoreRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * RAG 检索服务
 * 支持按知识库类型路由到不同的 Milvus Collection 进行语义检索
 *
 * @Author John
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final VectorStoreRouter vectorStoreRouter;

    public RagService(VectorStoreRouter vectorStoreRouter) {
        this.vectorStoreRouter = vectorStoreRouter;
    }

    /**
     * 从指定知识库中检索
     *
     * @param query         用户查询
     * @param topK          返回的最大文档数
     * @param knowledgeBase 目标知识库
     * @return 相关文档列表
     */
    public List<Document> retrieve(String query, int topK, KnowledgeBase knowledgeBase) {
        log.info("RAG 检索: kb={}, query='{}', topK={}", knowledgeBase, query, topK);
        VectorStore store = vectorStoreRouter.route(knowledgeBase);
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.5)
                .build();
        List<Document> results = store.similaritySearch(request);
        log.info("知识库 [{}] 检索到 {} 条相关文档", knowledgeBase.getDescription(), results.size());
        return results;
    }

    /**
     * 从默认知识库（GENERAL）中检索
     */
    public List<Document> retrieve(String query, int topK) {
        return retrieve(query, topK, KnowledgeBase.GENERAL);
    }

    /**
     * 跨多个知识库联合检索，按相似度合并排序
     *
     * @param query          用户查询
     * @param topK           最终返回的文档数
     * @param knowledgeBases 要检索的知识库列表
     * @return 合并排序后的文档列表
     */
    public List<Document> retrieveAcross(String query, int topK, KnowledgeBase... knowledgeBases) {
        log.info("跨库检索: kbs={}, query='{}'", List.of(knowledgeBases), query);
        List<Document> allResults = new ArrayList<>();
        for (KnowledgeBase kb : knowledgeBases) {
            allResults.addAll(retrieve(query, topK, kb));
        }
        // 按相似度分数降序排序，取 topK
        return allResults.stream()
                .sorted(Comparator.comparingDouble(
                        (Document d) -> d.getMetadata().containsKey("distance")
                                ? Double.parseDouble(d.getMetadata().get("distance").toString())
                                : 0.0).reversed())
                .limit(topK)
                .collect(Collectors.toList());
    }

    /**
     * 检索并拼接为上下文字符串，可直接注入到 Prompt 中
     */
    public String retrieveAsContext(String query, int topK, KnowledgeBase knowledgeBase) {
        List<Document> docs = retrieve(query, topK, knowledgeBase);
        if (docs.isEmpty()) {
            return "";
        }
        return docs.stream()
                .map(doc -> String.format("[来源: %s | 知识库: %s]\n%s",
                        doc.getMetadata().getOrDefault("source", "未知"),
                        knowledgeBase.getDescription(),
                        doc.getText()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

    /**
     * 构建 RAG 增强的系统提示词片段
     */
    public String buildRagPromptSection(String query, int topK, KnowledgeBase knowledgeBase) {
        String context = retrieveAsContext(query, topK, knowledgeBase);
        if (context.isEmpty()) {
            return "";
        }
        return """
                
                ## 参考资料
                以下是从知识库中检索到的相关信息，请优先参考这些内容回答用户问题。如果参考资料与问题无关，请忽略。
                
                %s
                """.formatted(context);
    }

    /**
     * 使用默认知识库构建 RAG 提示词
     */
    public String buildRagPromptSection(String query, int topK) {
        return buildRagPromptSection(query, topK, KnowledgeBase.GENERAL);
    }
}
