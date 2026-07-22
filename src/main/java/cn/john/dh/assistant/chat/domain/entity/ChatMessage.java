package cn.john.dh.assistant.chat.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import cn.john.dh.assistant.constant.ChatMessageType;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI对话消息实体
 *
 * @Author John
 * @Date 2026-07-20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息唯一标识
     */
    @TableField("message_id")
    private String messageId;

    /**
     * 所属会话ID
     */
    @TableField("conversation_id")
    private String conversationId;

    /**
     * 角色：USER/ASSISTANT
     */
    @TableField("type")
    private ChatMessageType type;

    /**
     * 消息内容
     */
    @TableField("content")
    private String content;

    /**
     * 改写后的内容
     */
    @TableField("transform_content")
    private String transformContent;

    /**
     * Token数量
     */
    @TableField("token_count")
    private Integer tokenCount;

    /**
     * 使用的模型名称
     */
    @TableField("model_name")
    private String modelName;

    /**
     * RAG引用内容JSON数组
     */
    @TableField("rag_references")
    private String ragReferences;

    /**
     * 扩展元数据JSON格式
     */
    @TableField("metadata")
    private String metadata;

}
