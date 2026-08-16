package cn.john.dh.assistant.chat.service.impl;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.mapper.ChatMessageMapper;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.constant.ChatMessageType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话消息 Service 实现类
 *
 * @Author John
 * @Date 2026-07-20
 */
@Service
public class ChatMessageServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageService {

    @Override
    public List<ChatMessage> listByConversationId(String conversationId, int maxMessages) {
        // 先按 id 倒序取最近 maxMessages 条，再反转回正序，保证返回的是"最近 N 条"而非"最早 N 条"
        List<ChatMessage> messages = list(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getId)
                .last("limit " + maxMessages));
        java.util.Collections.reverse(messages);
        return messages;
    }

    @Override
    public ChatMessage getByMessageId(String messageId) {
        return getOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId));
    }

    @Override
    public String saveMessage(String conversationId, ChatMessageType type, String content) {
        return saveMessage(conversationId, type, content, null);
    }

    @Override
    public String saveMessage(String conversationId, ChatMessageType type, String content, String metadata) {
        return saveMessage(conversationId, type, content, null, metadata);
    }

    @Override
    public String saveMessage(String conversationId, ChatMessageType type, String content, String thinkingContent, String metadata) {
        String messageId = UUID.randomUUID().toString().replace("-", "");
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setConversationId(conversationId);
        message.setType(type);
        message.setContent(content);
        message.setThinkingContent(thinkingContent);
        message.setMetadata(metadata);
        message.setCreatedAt(LocalDateTime.now());
        message.setUpdatedAt(LocalDateTime.now());

        this.save(message);
        return messageId;
    }

    @Override
    public boolean updateMessageDetail(String messageId, String transformContent, Integer tokenCount, String modelName) {
        return this.update(new LambdaUpdateWrapper<ChatMessage>()
                .eq(ChatMessage::getMessageId, messageId)
                .set(transformContent != null, ChatMessage::getTransformContent, transformContent)
                .set(tokenCount != null, ChatMessage::getTokenCount, tokenCount)
                .set(modelName != null, ChatMessage::getModelName, modelName)
                .set(ChatMessage::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean deleteByConversationId(String conversationId) {
        return this.remove(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getConversationId, conversationId));
    }

}
