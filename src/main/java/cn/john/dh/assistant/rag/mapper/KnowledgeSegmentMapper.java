package cn.john.dh.assistant.rag.mapper;

import cn.john.dh.assistant.rag.domain.entity.KnowledgeSegment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识片段 Mapper 接口
 *
 * @Author John
 * @Date 2026-07-30
 */
@Mapper
public interface KnowledgeSegmentMapper extends BaseMapper<KnowledgeSegment> {

}
