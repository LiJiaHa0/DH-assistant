package cn.john.dh.assistant.memory;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.constant.ChatMessageType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @Author John
 * @Date 2026-07-20 22:40
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseChatMemory implements ChatMemory {

    private final ChatMessageService chatMessageService;


    /**
     * 添加消息
     * @param conversationId
     * @param message
     */
    @Override
    public void add(String conversationId, Message message) {
        ChatMemory.super.add(conversationId, message);
    }

    /**
     * 添加消息列表
     * @param conversationId
     * @param messages
     */
    @Override
    public void add(String conversationId, List<Message> messages) {

    }

    /**
     *
     * @param conversationId
     * @return
     */
    @Override
    public List<Message> get(String conversationId) {
        List<Message> messageList = new ArrayList<>();
        List<ChatMessage> messages = chatMessageService.listByConversationId(conversationId,10);
        for(ChatMessage dbMessage : messages){
            if (dbMessage.getType() == ChatMessageType.USER) {
                messageList.add(new UserMessage(dbMessage.getContent()));
            } else if (dbMessage.getType() == ChatMessageType.ASSISTANT) {
                messageList.add(new AssistantMessage(dbMessage.getContent()));
            }
        }
        return messageList;
    }

    @Override
    public void clear(String conversationId) {

    }
}
