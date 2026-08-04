package cn.john.dh.assistant.rag.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文档版本实体（存储文档每个版本的快照信息）
 *
 * @Author John
 * @Date 2026-07-30
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document_version")
public class KnowledgeDocumentVersion extends BaseEntity {

    /**
     * 版本ID
     */
    @TableId(type = IdType.AUTO)
    private Long versionId;

    /**
     * 关联文档ID（knowledge_document.doc_id）
     */
    @TableField("doc_id")
    private Long docId;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;

    /**
     * 版本号（语义化版本，如 1.0.0）
     */
    @TableField("version")
    private String version;

    /**
     * 该版本文档URL（MinIO原始文件）
     */
    @TableField("doc_url")
    private String docUrl;

    /**
     * 该版本转换后的文档URL
     */
    @TableField("converted_doc_url")
    private String convertedDocUrl;

    /**
     * 该版本文档内容哈希值（SHA-256）
     */
    @TableField("content_hash")
    private String contentHash;

    /**
     * 版本状态：UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED, STORED
     */
    @TableField("status")
    private DocumentStatus status;

    /**
     * 该版本上传用户
     */
    @TableField("upload_user")
    private String uploadUser;

    /**
     * 版本变更说明
     */
    @TableField("changelog")
    private String changelog;
}
