package cn.john.dh.assistant.rag.controller;

import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.service.DocumentIngestionService;
import cn.john.dh.assistant.rag.service.RagService;
import org.springframework.ai.document.Document;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库管理接口
 *
 * @Author John
 */
@RestController
@RequestMapping("/api/rag")
public class RagController {

    private final DocumentIngestionService ingestionService;
    private final RagService ragService;

    public RagController(DocumentIngestionService ingestionService, RagService ragService) {
        this.ingestionService = ingestionService;
        this.ragService = ragService;
    }

    /**
     * 上传文档到指定知识库
     *
     * @param file     文件（PDF/DOCX/TXT/MD等）
     * @param kb       知识库类型（GENERAL/PRODUCT/TECH），默认 GENERAL
     * @param category 文档分类标签
     */
    @PostMapping("/documents/upload")
    public R<Map<String, Object>> uploadDocument(@RequestParam("file") MultipartFile file, @RequestParam(value = "kb", required = false, defaultValue = "GENERAL") KnowledgeBase kb, @RequestParam(value = "category", required = false, defaultValue = "general") String category) throws IOException {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", file.getOriginalFilename());
        metadata.put("category", category);
        metadata.put("uploadTime", System.currentTimeMillis());

        int chunks = ingestionService.ingest(file.getResource(), metadata, kb);

        Map<String, Object> result = new HashMap<>();
        result.put("filename", file.getOriginalFilename());
        result.put("knowledgeBase", kb.name());
        result.put("collection", kb.getCollectionName());
        result.put("chunks", chunks);
        return R.ok(result);
    }

    /**
     * 摄入纯文本到指定知识库
     */
    @PostMapping("/documents/text")
    public R<Map<String, Object>> ingestText(@RequestParam("content") String content, @RequestParam(value = "kb", required = false, defaultValue = "GENERAL") KnowledgeBase kb, @RequestParam(value = "source", required = false, defaultValue = "manual-input") String source) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("source", source);
        metadata.put("uploadTime", System.currentTimeMillis());

        int chunks = ingestionService.ingestText(content, metadata, kb);

        Map<String, Object> result = new HashMap<>();
        result.put("source", source);
        result.put("knowledgeBase", kb.name());
        result.put("chunks", chunks);
        return R.ok(result);
    }

    /**
     * 从指定知识库检索
     */
    @GetMapping("/search")
    public R<List<Document>> search(@RequestParam("query") String query, @RequestParam(value = "kb", required = false, defaultValue = "GENERAL") KnowledgeBase kb, @RequestParam(value = "topK", required = false, defaultValue = "5") int topK) {
        List<Document> results = ragService.retrieve(query, topK, kb);
        return R.ok(results);
    }

    /**
     * 跨知识库联合检索
     */
    @GetMapping("/search/cross")
    public R<List<Document>> crossSearch(@RequestParam("query") String query, @RequestParam(value = "topK", required = false, defaultValue = "5") int topK) {
        List<Document> results = ragService.retrieveAcross(query, topK, KnowledgeBase.values());
        return R.ok(results);
    }

    /**
     * 查看所有知识库信息
     */
    @GetMapping("/knowledge-bases")
    public R<List<Map<String, String>>> listKnowledgeBases() {
        List<Map<String, String>> list = java.util.Arrays.stream(KnowledgeBase.values()).map(kb -> Map.of("name", kb.name(), "collection", kb.getCollectionName(), "description", kb.getDescription())).toList();
        return R.ok(list);
    }
}
