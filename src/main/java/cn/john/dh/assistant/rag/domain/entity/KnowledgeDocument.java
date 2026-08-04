package cn.john.dh.assistant.rag.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import cn.john.dh.assistant.rag.config.KnowledgeBase;
import cn.john.dh.assistant.rag.domain.enums.DocumentStatus;
import cn.john.dh.assistant.rag.domain.enums.KnowledgeBaseType;
import cn.john.dh.assistant.rag.domain.record.KnowledgeUploadParam;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * 知识文档实体
 *
 * @Author John
 * @Date 2026-07-30
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("knowledge_document")
public class KnowledgeDocument extends BaseEntity {

    /**
     * 文档ID
     */
    @TableId(type = IdType.AUTO)
    private Long docId;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;

    /**
     * 文档标题
     */
    @TableField("doc_title")
    private String docTitle;

    /**
     * 文档类型
     */
    private KnowledgeBase docType;

    /**
     * 文档失效日期
     */
    @TableField("expire_date")
    private LocalDate expireDate;

    /**
     * 状态：INIT, UPLOADED, CONVERTING, CONVERTED, CHUNKED, VECTOR_STORED
     */
    @TableField("status")
    private DocumentStatus status;

    /**
     * 可见范围
     */
    @TableField("accessible_by")
    private String accessibleBy;

    /**
     * 文档描述
     */
    @TableField("description")
    private String description;

    /**
     * 知识库类型：DOCUMENT_SEARCH, DATA_QUERY
     */
    @TableField("knowledge_base_type")
    private KnowledgeBaseType knowledgeBaseType;

    /**
     * 扩展字段，保存JSON字符串
     */
    @TableField("extension")
    private String extension;

    /**
     * 当前激活版本ID，指向 knowledge_document_version.version_id
     */
    @TableField("current_version_id")
    private Long currentVersionId;

    public KnowledgeDocument create(KnowledgeUploadParam documentUploadParam) {
        this.setDocTitle(documentUploadParam.title());
        this.setStatus(DocumentStatus.UPLOADED);
        this.setDescription(documentUploadParam.description());
        this.setKnowledgeBaseType(KnowledgeBaseType.valueOf(documentUploadParam.knowledgeBaseType()));
        // 设置知识库（向量库）类型，决定文档路由到哪个 Milvus Collection
        String docType = documentUploadParam.docType();
        if (docType != null && !docType.isBlank()) {
            this.setDocType(KnowledgeBase.valueOf(docType));
        }
        this.setTableName(documentUploadParam.tableName());
        return this;
    }

    @JsonIgnore
    public void setTableName(String tableName) {
        Map<String, Serializable> extensionMap;
        if (extension == null) {
            extensionMap = new HashMap<String, Serializable>();
        } else {
            extensionMap = JSON.parseObject(extension, Map.class);
        }
        extensionMap.put("tableName", tableName);
        this.extension = JSON.toJSONString(extensionMap);
    }

    @JsonIgnore
    public String getTableName() {
        if (extension != null && !extension.isEmpty()) {
            return (String) JSON.parseObject(extension, Map.class).get("tableName");
        }
        return null;
    }
}
