package cn.john.dh.assistant.rag.service;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * 知识片段 Service 接口
 *
 * @Author John
 * @Date 2026-07-30
 */
public interface KnowledgeSegmentService extends IService<KnowledgeSegment> {

    /**
     * 更新分段文本内容
     * <p>
     * 父分段（skipEmbedding=1）禁止直接编辑；子分段文本变更时自动同步父分段文本，
     * 并在已向量化的情况下重新生成向量。
     *
     * @param id      分段ID
     * @param newText 新文本内容
     * @return 是否更新成功
     */
    boolean updateSegmentText(Long id, String newText);
}
