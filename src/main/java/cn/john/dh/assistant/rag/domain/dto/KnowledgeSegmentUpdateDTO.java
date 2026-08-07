package cn.john.dh.assistant.rag.domain.dto;

import lombok.Data;

/**
 * 知识分段编辑请求数据传输对象
 * <p>
 * 用于封装前端编辑分段时提交的请求参数，仅支持修改分段文本内容。
 * 父分段（skipEmbedding=1）不允许直接编辑，修改子分段时父分段文本会自动同步。
 * </p>
 *
 * @Author John
 * @Date 2026-08-07
 */
@Data
public class KnowledgeSegmentUpdateDTO {

    /**
     * 分段ID
     */
    private Long id;

    /**
     * 分段文本内容
     */
    private String text;

    /**
     * 乐观锁版本号
     */
    private Integer lockVersion;
}
