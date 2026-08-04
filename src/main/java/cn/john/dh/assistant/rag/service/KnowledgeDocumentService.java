package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

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



}
