package cn.john.dh.assistant.rag.domain.dto;

import lombok.Data;

/**
 * 知识文档编辑请求数据传输对象
 * <p>
 * 用于封装前端编辑文档时提交的请求参数，目前仅支持修改文档标题和描述。
 * </p>
 *
 * @Author John
 * @Date 2026-08-05
 */
@Data
public class KnowledgeDocumentUpdateDTO {

    /**
     * 文档ID
     */
    private Long docId;

    /**
     * 文档标题
     */
    private String docTitle;

    /**
     * 文档描述
     */
    private String description;
}
