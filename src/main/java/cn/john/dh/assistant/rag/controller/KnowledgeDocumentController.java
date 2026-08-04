package cn.john.dh.assistant.rag.controller;

import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 知识文档管理接口
 * @Author John
 * @Date 2026-07-30 18:27
 */

@RestController
@RequestMapping("/knowledge/document")
public class KnowledgeDocumentController {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @PostMapping("/upload")
    public R<KnowledgeDocument> uploadDocument(@RequestParam("file") MultipartFile file,
                                            @RequestParam("title") String title,
                                            @RequestParam("description") String description,
                                            @RequestParam("knowledgeBaseType") String knowledgeBaseType,
                                            @RequestParam(value = "docType", defaultValue = "GENERAL") String docType,
                                            @RequestParam(value = "tableName", required = false) String tableName,
                                            @RequestParam(value = "version", required = false, defaultValue = "1.0.0") String version) throws IOException {
        return R.ok(knowledgeDocumentService.uploadDocument(new KnowledgeUploadParam(file, title, description, knowledgeBaseType, docType, tableName, version)));
    }

    /**
     * 上传文档新版本
     *
     * @param file        新版本文件
     * @param docId       文档ID（knowledge_document.doc_id）
     * @param version     新版本号（语义化版本，如 "2.0.0"，必须大于现有最新版本号）
     * @param changelog   版本变更说明（可选）
     * @return 更新后的文档记录
     */
    @PostMapping("/upload-version")
    public R<KnowledgeDocument> uploadVersion(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docId") Long docId,
            @RequestParam("version") String version,
            @RequestParam(value = "changelog", required = false) String changelog) throws IOException {
        return R.ok(knowledgeDocumentService.uploadNewVersion(docId, version, file, changelog));
    }

    /**
     * 分页查询知识文档列表（支持多条件筛选）
     *
     * @param current           当前页
     * @param size              每页大小
     * @param docTitle          文档标题（模糊查询，可选）
     * @param status            文档状态（可选）
     * @param knowledgeBaseType 知识库类型（可选）
     * @return 分页结果
     */
    @GetMapping("/page")
    public R<Page<KnowledgeDocument>> page(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(value = "docTitle", required = false) String docTitle,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "knowledgeBaseType", required = false) String knowledgeBaseType) {
        Page<KnowledgeDocument> page = new Page<>(current, size);
        QueryWrapper<KnowledgeDocument> wrapper = new QueryWrapper<>();
        if (docTitle != null && !docTitle.isEmpty()) {
            wrapper.like("doc_title", docTitle);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq("status", status);
        }
        if (knowledgeBaseType != null && !knowledgeBaseType.isEmpty()) {
            wrapper.eq("knowledge_base_type", knowledgeBaseType);
        }
        wrapper.orderByDesc("created_at");
        return R.ok(knowledgeDocumentService.page(page, wrapper));
    }
}
