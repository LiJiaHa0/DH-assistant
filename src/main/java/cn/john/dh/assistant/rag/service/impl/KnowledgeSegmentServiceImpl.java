package cn.john.dh.assistant.rag.service.impl;

import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.constant.MetadataKeyConstant;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeDocument;
import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import cn.john.dh.assistant.rag.mapper.KnowledgeSegmentMapper;
import cn.john.dh.assistant.rag.service.DocumentIngestionService;
import cn.john.dh.assistant.rag.service.KnowledgeDocumentService;
import cn.john.dh.assistant.rag.service.KnowledgeSegmentService;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识片段 Service 实现类
 *
 * @Author John
 * @Date 2026-07-30
 */
@Service
public class KnowledgeSegmentServiceImpl extends ServiceImpl<KnowledgeSegmentMapper, KnowledgeSegment> implements KnowledgeSegmentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSegmentServiceImpl.class);

    @Autowired
    private KnowledgeDocumentService knowledgeDocumentService;

    @Autowired
    private DocumentIngestionService documentIngestionService;

    /**
     * 更新分段文本内容
     * <p>
     * 处理规则：
     * 1. 父分段（skipEmbedding=1）存储完整原文作为上下文容器，禁止直接编辑；
     * 2. 子分段文本变更时，自动将父分段中对应的旧文本替换为新文本，保证父子内容一致；
     * 3. 子分段已向量化时，删除旧向量并重新嵌入，保证检索内容与数据库一致。
     *
     * @param id      分段ID
     * @param newText 新文本内容
     * @return 是否更新成功
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateSegmentText(Long id, String newText) {
        KnowledgeSegment segment = this.getById(id);
        if (segment == null) {
            throw new BusinessException("分段不存在: id=" + id);
        }
        // 父分段禁止直接编辑，需通过修改子分段自动同步
        if (segment.getSkipEmbedding() != null && segment.getSkipEmbedding() == 1) {
            throw new BusinessException("父分段不支持直接编辑，请修改对应的子分段，父分段内容会自动同步");
        }
        // 文本未变更，无需处理
        if (Objects.equals(segment.getText(), newText)) {
            return true;
        }
        String oldText = segment.getText();

        // 1. 更新分段文本
        segment.setText(newText);
        boolean updated = this.updateById(segment);

        // 2. 文本变更时，同步更新父分段内容
        syncParentSegmentText(segment, oldText, newText);

        // 3. 已向量化的分段重新嵌入，向量操作失败仅记录日志，不回滚数据库更新
        if (StringUtils.hasText(segment.getEmbeddingId())) {
            try {
                KnowledgeDocument document = knowledgeDocumentService.getById(segment.getDocumentId());
                KnowledgeBase knowledgeBase = (document != null && document.getDocType() != null)
                        ? document.getDocType() : KnowledgeBase.GENERAL;
                documentIngestionService.reEmbedSegment(segment, knowledgeBase);
            } catch (Exception e) {
                log.error("分段向量更新失败, segmentId={}, error={}", id, e.getMessage(), e);
            }
        }

        return updated;
    }

    /**
     * 当子分段文本变更时，同步更新对应父分段的文本内容。
     * <p>
     * 父分段存储完整文本（skipEmbedding=1），子分段是其中的子串。
     * 修改子分段时将父分段中对应的旧文本替换为新文本，保证检索时获取的完整上下文是最新的。
     *
     * @param segment 子分段（包含 metadata）
     * @param oldText 修改前的旧文本
     * @param newText 修改后的新文本
     */
    private void syncParentSegmentText(KnowledgeSegment segment, String oldText, String newText) {
        if (!StringUtils.hasText(segment.getMetadata())) {
            return;
        }
        Map<String, Object> metadataMap;
        try {
            metadataMap = JSON.parseObject(segment.getMetadata(), Map.class);
        } catch (Exception e) {
            log.warn("解析分段 metadata 失败, segmentId={}", segment.getId(), e);
            return;
        }
        Object parentChunkIdObj = metadataMap.get(MetadataKeyConstant.PARENT_CHUNK_ID);
        if (parentChunkIdObj == null) {
            // 非子分段（无父分片关联），无需同步
            return;
        }
        String parentChunkId = String.valueOf(parentChunkIdObj);

        // 查找父分段
        QueryWrapper<KnowledgeSegment> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("chunk_id", parentChunkId);
        KnowledgeSegment parentSegment = this.getOne(queryWrapper);
        if (parentSegment == null) {
            log.warn("子分段修改后同步父分段失败：未找到父分段, parentChunkId={}", parentChunkId);
            return;
        }

        // 将父分段文本中的旧子分段文本替换为新文本
        String parentText = parentSegment.getText();
        String updatedParentText = parentText.replaceFirst(
                Pattern.quote(oldText), Matcher.quoteReplacement(newText));
        if (!updatedParentText.equals(parentText)) {
            parentSegment.setText(updatedParentText);
            this.updateById(parentSegment);
            log.info("子分段修改已同步更新父分段, parentChunkId={}, parentSegmentId={}", parentChunkId, parentSegment.getId());
        } else {
            log.warn("子分段修改后父分段文本未匹配到旧文本，跳过同步, parentChunkId={}, segmentId={}", parentChunkId, segment.getId());
        }
    }
}
