package cn.john.dh.assistant.rag.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.rag.domain.dto.KnowledgeDocumentUpdateDTO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.record.DocumentSplitParam;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * 知识文档管理接口
 *
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
     * 上传文档新版本（仅限文档所属用户）
     *
     * @param file      新版本文件
     * @param docId     文档ID（knowledge_document.doc_id）
     * @param version   新版本号（语义化版本，如 "2.0.0"，必须大于现有最新版本号）
     * @param changelog 版本变更说明（可选）
     * @return 更新后的文档记录
     */
    @PostMapping("/upload-version")
    public R<KnowledgeDocument> uploadVersion(
            @RequestParam("file") MultipartFile file,
            @RequestParam("docId") Long docId,
            @RequestParam("version") String version,
            @RequestParam(value = "changelog", required = false) String changelog) throws IOException {
        checkDocumentOwner(docId);
        return R.ok(knowledgeDocumentService.uploadNewVersion(docId, version, file, changelog));
    }

    /**
     * 对文档进行切分（仅限文档所属用户）
     * 注意：此方法为手动触发切分接口，正常流程由事件驱动自动执行
     *
     * @param documentId 文档ID
     * @return 切分后的片段数量
     */
    @PostMapping("/split/{documentId}")
    public R<Integer> splitDocument(@PathVariable Long documentId,
                                    @RequestParam("splitType") String splitType,
                                    @RequestParam("chunkSize") Integer chunkSize,
                                    @RequestParam(value = "overlap", required = false) Integer overlap,
                                    @RequestParam(value = "regex", required = false) String regex,
                                    @RequestParam(value = "titleLevel", required = false) Integer titleLevel,
                                    @RequestParam(value = "separator", required = false) String separator) {
        // 参数校验：分块大小与重叠值必须合法，避免切分器异常或死循环
        if (splitType == null || splitType.isBlank()) {
            return R.fail("切分方式不能为空");
        }
        if (chunkSize == null || chunkSize < 10) {
            return R.fail("分块大小不能小于10");
        }
        if (overlap != null && (overlap < 0 || overlap >= chunkSize)) {
            return R.fail("重叠大小必须大于等于0且小于分块大小");
        }
        checkDocumentOwner(documentId);
        int count = knowledgeDocumentService.split(documentId, new DocumentSplitParam(splitType, chunkSize, overlap, titleLevel, separator, regex));
        return R.ok(count);
    }

    /**
     * 分页查询知识文档列表（仅返回当前用户的文档）
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
        // 按用户隔离：只能看到自己的文档
        wrapper.eq("user_id", StpUtil.getLoginIdAsString());
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

    /**
     * 编辑文档基础信息（标题、描述，仅限文档所属用户）
     *
     * @param dto 编辑请求参数
     * @return 更新后的文档记录
     */
    @PutMapping("/update")
    public R<KnowledgeDocument> updateDocument(@RequestBody KnowledgeDocumentUpdateDTO dto) {
        if (dto.getDocId() == null) {
            return R.fail("文档ID不能为空");
        }
        checkDocumentOwner(dto.getDocId());
        return R.ok(knowledgeDocumentService.updateDocument(dto));
    }

    /**
     * 删除单个文档（级联逻辑删除关联的版本和分段，仅限文档所属用户）
     *
     * @param docId 文档ID
     * @return 删除结果
     */
    @DeleteMapping("/delete")
    public R<Void> deleteDocument(@RequestParam("docId") Long docId) {
        checkDocumentOwner(docId);
        boolean success = knowledgeDocumentService.removeDocumentWithSegments(docId);
        if (!success) {
            return R.fail("文档删除失败，可能文档不存在");
        }
        return R.ok();
    }

    /**
     * 批量删除文档（级联逻辑删除关联的版本和分段，仅限文档所属用户）
     *
     * @param docIds 文档ID列表
     * @return 成功删除的数量
     */
    @DeleteMapping("/batch-delete")
    public R<Integer> batchDeleteDocuments(@RequestBody List<Long> docIds) {
        if (docIds == null || docIds.isEmpty()) {
            return R.fail("文档ID列表不能为空");
        }
        for (Long docId : docIds) {
            checkDocumentOwner(docId);
        }
        int count = knowledgeDocumentService.batchRemoveDocumentsWithSegments(docIds);
        return R.ok(count);
    }

    /**
     * 校验文档归属：文档必须存在且属于当前登录用户
     */
    private void checkDocumentOwner(Long docId) {
        KnowledgeDocument document = knowledgeDocumentService.getById(docId);
        if (document == null) {
            throw new BusinessException("文档不存在: docId=" + docId);
        }
        if (!Objects.equals(document.getUserId(), StpUtil.getLoginIdAsString())) {
            throw new BusinessException("无权操作该文档");
        }
    }
}
