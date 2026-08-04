package cn.john.dh.assistant.rag.service.impl;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocumentVersion;
import cn.john.dh.assistant.rag.mapper.KnowledgeDocumentVersionMapper;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentVersionService;
import cn.john.dh.assistant.utils.VersionUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
public class KnowledgeDocumentVersionServiceImpl extends ServiceImpl<KnowledgeDocumentVersionMapper, KnowledgeDocumentVersion> implements KnowledgeDocumentVersionService {

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

    @Override
    public List<KnowledgeDocumentVersion> listByDocId(Long docId) {
        List<KnowledgeDocumentVersion> versions = list(new QueryWrapper<KnowledgeDocumentVersion>()
                .eq("doc_id", docId));
        // 在 Java 层按语义版本降序排序
        versions.sort(VERSION_COMPARATOR.reversed());
        return versions;
    }

    @Override
    public String getLatestVersion(Long docId) {
        List<KnowledgeDocumentVersion> versions = listByDocId(docId);
        if (versions.isEmpty()) {
            return null;
        }
        return versions.get(0).getVersion();    }
}
