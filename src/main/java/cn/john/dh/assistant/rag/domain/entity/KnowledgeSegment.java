package cn.john.dh.assistant.rag.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import cn.john.dh.assistant.rag.domain.enums.SegmentStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 知识片段实体
 *
 * @Author John
 * @Date 2026-07-30
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_segment")
public class KnowledgeSegment extends BaseEntity {

    /**
     * 片段ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 文本内容
     */
    @TableField("`text`")
    private String text;

    /**
     * 分片ID
     */
    @TableField("chunk_id")
    private String chunkId;

    /**
     * 元数据
     */
    @TableField("`metadata`")
    private String metadata;

    /**
     * 所属文档ID
     */
    @TableField("document_id")
    private Long documentId;

    /**
     * 所属文档版本ID（knowledge_document_version.version_id）
     */
    @TableField("document_version")
    private Long documentVersion;

    /**
     * 顺序
     */
    @TableField("chunk_order")
    private Integer chunkOrder;

    /**
     * 嵌入ID
     */
    @TableField("embedding_id")
    private String embeddingId;

    /**
     * 状态：STORED, VECTOR_STORED
     */
    @TableField("status")
    private SegmentStatus status;

    /**
     * 是否跳过嵌入生成
     */
    @TableField("skip_embedding")
    private Integer skipEmbedding;
}
