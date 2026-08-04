package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * 文档版本 Service 接口
 *
 * @Author John
 * @Date 2026-07-30
 */
public interface KnowledgeDocumentVersionService extends IService<KnowledgeDocumentVersion> {

    /**
     * 检查内容哈希是否已存在
     *
     * @param contentHash 内容哈希值
     * @return 是否已存在
     */
    boolean existsByContentHash(String contentHash);

    /**
     * 查询文档的所有版本（按版本号降序）
     *
     * @param docId 文档ID
     * @return 版本列表
     */
    List<KnowledgeDocumentVersion> listByDocId(Long docId);

    /**
     * 获取文档的最新版本号
     *
     * @param docId 文档ID
     * @return 最新版本号（如 "2.0.0"），无版本记录时返回 null
     */
    String getLatestVersion(Long docId);
}
