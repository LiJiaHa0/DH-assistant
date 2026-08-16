package cn.john.dh.assistant.rag.service.impl;

import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.SegmentStatus;
import cn.john.dh.assistant.rag.mapper.KnowledgeDocumentVersionMapper;
import cn.john.dh.assistant.rag.service.DocumentIngestionService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import cn.john.dh.assistant.utils.VersionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 文档版本 Service 实现类
 *
 * @Author John
 * @Date 2026-07-30
 */
@Service
@Slf4j
public class KnowledgeDocumentVersionServiceImpl extends ServiceImpl<KnowledgeDocumentVersionMapper, KnowledgeDocumentVersion> implements KnowledgeDocumentVersionService {

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private KnowledgeSegmentService knowledgeSegmentService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    /**
     * 语义化版本比较器（按 major.minor.patch 数值比较）
     */
    private static final Comparator<KnowledgeDocumentVersion> VERSION_COMPARATOR =
            Comparator.comparing(KnowledgeDocumentVersion::getVersion, VersionUtil::compareVersions);

    /**
     * 检查内容哈希是否已存在
     *
     * @param contentHash 内容哈希值
     * @return 是否已存在
     */
    @Override
    public boolean existsByContentHash(String contentHash) {
        return count(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("content_hash", contentHash)) > 0;    }

    /**
     * 查询文档的所有版本（按版本号降序）
     *
     * @param docId 文档ID
     * @return 版本列表
     */
    @Override
    public List<KnowledgeDocumentVersion> listByDocId(Long docId) {
        List<KnowledgeDocumentVersion> versions = list(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("doc_id", docId));
        // 在 Java 层按语义版本降序排序
        versions.sort(VERSION_COMPARATOR.reversed());
        return versions;
    }

    /**
     * 获取文档的最新版本号
     * @param docId 文档ID
     * @return
     */
    @Override
    public String getLatestVersion(Long docId) {
        List<KnowledgeDocumentVersion> versions = listByDocId(docId);
        if (versions.isEmpty()) {
            return null;
        }
        return versions.get(0).getVersion();
    }

    /**
     * 对文档进行版本embedding
     * <p>
     * 流程：先嵌入新版本向量 → double-check 验证 → 成功后升级版本状态并清理旧版本向量
     * <p>
     * 只有在新版本嵌入成功后才删除旧版本向量，避免新向量存储失败导致数据丢失。
     *
     * @param docVersionId 文档版本ID
     * @param knowledgeDocument 文档实体
     * @return 是否成功
     */
    @Override
    public boolean embeddingVersion(Long docVersionId, KnowledgeDocument knowledgeDocument) {
        KnowledgeDocumentVersion knowledgeVersion = getById(docVersionId);
        //如果文档版本不存在
        if (knowledgeVersion == null) {
            return false;
        }
        //如果文档版本状态已为VECTOR_STORED，无需重复向量化
        if (knowledgeVersion.getStatus() == DocumentStatus.VECTOR_STORED) {
            log.info("文档版本状态已为VECTOR_STORED，无需重复向量化: {}", knowledgeVersion.getId());
            return true;
        //如果文档版本状态不是CHUNKED，无法完成向量化
        }
        if (knowledgeVersion.getStatus() != DocumentStatus.CHUNKED) {
            log.warn("文档版本状态不是CHUNKED，无法完成向量化: {}", knowledgeVersion.getStatus());
            return false;
        }

        // 让文档版本生效（嵌入新向量）
        knowledgeDocumentService.activateVersion(knowledgeVersion.getId(), knowledgeDocument);

        //double check
        long segmentCount = knowledgeSegmentService.count(new QueryWrapper<KnowledgeSegment>()
                .eq("document_id", knowledgeVersion.getDocId())
                .eq("document_version", knowledgeVersion.getId())
                .eq("status", SegmentStatus.STORED)
                .eq("skip_embedding", 0));

        if (segmentCount == 0) {
            // 新版本嵌入成功，升级当前版本状态为 VECTOR_STORED
            knowledgeVersion.setStatus(DocumentStatus.VECTOR_STORED);
            updateById(knowledgeVersion);
            // 针对非当前版本的旧版本，执行失效操作（删除旧向量并降级状态）
            List<KnowledgeDocumentVersion> oldVersions = list(new QueryWrapper<KnowledgeDocumentVersion>()
                    .eq("doc_id", knowledgeVersion.getDocId())
                    .eq("status", DocumentStatus.VECTOR_STORED)
                    .ne("id", knowledgeVersion.getId()));
            oldVersions.forEach(version -> knowledgeDocumentService.deactivateVersion(version.getId(), knowledgeDocument.getDocType()));
            return true;
        }

        log.warn("向量存储失败，存在部分分段没有存储成功，未成功的数量： " + segmentCount);
        return false;
    }

    /**
     * 清理文档的旧版本向量数据（保留当前版本）
     * <p>修复：原实现误删了当前版本的向量（参数名即 currentVersionId），
     * 与"清理旧版本"的语义相反；此处遍历所有版本，仅删除非当前版本。</p>
     *
     * @param docId            文档ID
     * @param currentVersionId 需要保留的当前版本ID
     * @param knowledgeBase    文档所属知识库
     */
    @Override
    public boolean cleanupOldVersionData(Long docId, Long currentVersionId, KnowledgeBase knowledgeBase) {
        log.info("开始清理文档 {} 的旧版本数据（保留 versionId={}）", docId, currentVersionId);
        List<KnowledgeDocumentVersion> versions = listByDocId(docId);
        int cleaned = 0;
        for (KnowledgeDocumentVersion version : versions) {
            if (version.getId().equals(currentVersionId)) {
                continue; // 保留当前版本
            }
            documentIngestionService.deleteByDocIdAndVersionId(docId, version.getId(), knowledgeBase);
            cleaned++;
        }
        log.info("清理文档 {} 旧版本向量完成，共清理 {} 个版本", docId, cleaned);
        return true;
    }
}
