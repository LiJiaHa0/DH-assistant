package cn.john.dh.assistant.chat.service.impl;

import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.mapper.ChatConversationMapper;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.constant.ChatConversationStatus;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI对话会话 Service 实现类
 *
 * @Author John
 * @Date 2026-07-19
 */
@Service
public class ChatConversationServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationService {

    @Override
    public List<ChatConversation> listByUserId(String userId) {
        return list(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getUserId, userId)
                .orderByDesc(ChatConversation::getCreatedAt));
    }

    @Override
    public ChatConversation getByConversationId(String conversationId) {
        return getOne(new LambdaQueryWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId));
    }

    @Override
    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString().replace("-", "") + userId;
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId);
        conversation.setUserId(userId);
        conversation.setTitle(title != null ? title : "新对话");
        conversation.setStatus(ChatConversationStatus.ACTIVE);
        conversation.setCreatedAt(LocalDateTime.now());
        conversation.setUpdatedAt(LocalDateTime.now());
        this.save(conversation);
        return conversationId;
    }

    @Override
    public boolean updateTitle(String conversationId, String title) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getTitle, title)
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean archiveConversation(String conversationId) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getStatus, "archived")
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        return this.update(new LambdaUpdateWrapper<ChatConversation>()
                .eq(ChatConversation::getConversationId, conversationId)
                .set(ChatConversation::getStatus, "deleted")
                .set(ChatConversation::getUpdatedAt, LocalDateTime.now()));
    }

}
