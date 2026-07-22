package cn.john.dh.assistant.chat.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import cn.john.dh.assistant.constant.ChatConversationStatus;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI对话会话实体
 *
 * @Author John
 * @Date 2026-07-19
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话唯一标识
     */
    @TableField("conversation_id")
    private String conversationId;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;

    /**
     * 会话标题
     */
    @TableField("title")
    private String title;

    /**
     * 状态
     */
    @TableField("status")
    private ChatConversationStatus status;

}
