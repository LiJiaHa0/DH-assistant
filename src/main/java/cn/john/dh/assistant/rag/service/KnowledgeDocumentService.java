package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.domain.dto.KnowledgeDocumentUpdateDTO;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.record.DocumentSplitParam;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 知识文档 Service 接口
 *
 * @Author John
 * @Date 2026-07-30
 */
public interface KnowledgeDocumentService extends IService<KnowledgeDocument> {

    /**
     * 上传文档
     * @param param
     * @return
     * @throws IOException
     */
    KnowledgeDocument uploadDocument(KnowledgeUploadParam param) throws IOException;

    /**
     * 上传文档新版本
     * @param docId     文档ID（knowledge_document.doc_id）
     * @param version   新版本号（语义化版本，如 "2.0.0"，必须大于现有最大版本号）
     * @param file      新版本文件
     * @param changelog 版本变更说明（可选）
     * @return 更新后的文档记录
     * @throws IOException IO异常
     */
    KnowledgeDocument uploadNewVersion(Long docId, String version, MultipartFile file, String changelog) throws IOException;

    /**
     * 文档分段
     *
     * @param documentId
     * @param documentSplitParam
     * @return
     */
    int split(Long documentId, DocumentSplitParam documentSplitParam);

    /**
     * 删除文档，并级联逻辑删除该文档下的所有分段
     *
     * @param docId 文档ID
     * @return 是否删除成功
     */
    boolean removeDocumentWithSegments(Long docId);

    /**
     * 同步推进文档和指定版本的状态。
     * 仅当当前状态按生命周期顺序早于目标状态时才会更新；若当前状态已大于或等于目标状态，则跳过，避免状态回退。
     *
     * @param docId        文档ID
     * @param versionId    版本ID（knowledge_document_version.version_id）
     * @param targetStatus 目标状态，如 CONVERTING、CONVERTED、CHUNKED、VECTOR_STORED、STORED
     * @return 是否执行了更新（true：文档或版本至少有一个被更新；false：均未更新）
     */
    boolean advanceDocumentAndVersionStatus(Long docId, Long versionId, DocumentStatus targetStatus);

    /**
     * 编辑文档基础信息（标题、描述）
     *
     * @param dto 编辑请求参数
     * @return 更新后的文档记录
     */
    KnowledgeDocument updateDocument(KnowledgeDocumentUpdateDTO dto);

    /**
     * 批量删除文档，并级联逻辑删除关联的版本和分段
     *
     * @param docIds 文档ID列表
     * @return 成功删除的数量
     */
    int batchRemoveDocumentsWithSegments(List<Long> docIds);

    /**
     * 让指定版本生效（重新向量化）：
     * 1. 校验版本状态必须为 CHUNKED
     * 2. 对该版本下所有 STORED 且未向量化的分段重新 embed 并写入 ES
     * 3. 将分段状态更新为 VECTOR_STORED
     * 4. 将版本记录状态从 CHUNKED 升为 VECTOR_STORED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     */
    void activateVersion(Long versionId, KnowledgeDocument knowledgeDocument);

    /**
     * 让指定版本失效：
     * 1. 清理该版本在 ES 中的向量数据
     * 2. 将该版本下所有分段状态从 VECTOR_STORED 降为 STORED，并清空 embeddingId
     * 3. 将版本记录状态从 VECTOR_STORED 降为 CHUNKED
     *
     * @param versionId 版本ID（knowledge_document_version.version_id）
     * @param knowledgeBase 知识库信息
     */
    void deactivateVersion(Long versionId, KnowledgeBase knowledgeBase);

}
