package cn.john.dh.assistant.chat.service;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.constant.ChatMessageType;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * AI对话消息 Service 接口
 *
 * @Author John
 * @Date 2026-07-20
 */
public interface ChatMessageService extends IService<ChatMessage> {

    /**
     * 根据会话ID查询消息列表（按创建时间升序）
     *
     * @param conversationId 会话唯一标识
     * @return 消息列表
     */
    List<ChatMessage> listByConversationId(String conversationId, int maxMessages);

    /**
     * 根据消息唯一标识查询消息
     *
     * @param messageId 消息唯一标识
     * @return 消息信息，不存在则返回 null
     */
    ChatMessage getByMessageId(String messageId);

    /**
     * 保存一条对话消息
     *
     * @param conversationId 所属会话ID
     * @param type           角色：USER/ASSISTANT
     * @param content        消息内容
     * @return 消息唯一标识 messageId
     */
    String saveMessage(String conversationId, ChatMessageType type, String content);

    /**
     * 保存一条对话消息（含元数据）
     *
     * @param conversationId 所属会话ID
     * @param type           角色：USER/ASSISTANT
     * @param content        消息内容
     * @param metadata       扩展元数据JSON字符串（可存储思考过程、推荐问题、参考来源等）
     * @return 消息唯一标识 messageId
     */
    String saveMessage(String conversationId, ChatMessageType type, String content, String metadata);

    /**
     * 更新消息内容（如改写内容、补充 token 统计等）
     *
     * @param messageId       消息唯一标识
     * @param transformContent 改写后的内容
     * @param tokenCount      Token数量
     * @param modelName       模型名称
     * @return 是否成功
     */
    boolean updateMessageDetail(String messageId, String transformContent, Integer tokenCount, String modelName);

    /**
     * 根据会话ID删除所有消息
     *
     * @param conversationId 会话唯一标识
     * @return 是否成功
     */
    boolean deleteByConversationId(String conversationId);

}
