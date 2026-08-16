package cn.john.dh.assistant.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

/**
 * AI对话消息控制器
 *
 * @Author John
 * @Date 2026-07-20
 */
@RestController
@RequestMapping("/chat/message")
public class ChatMessageController {

    @Autowired
    private ChatMessageService chatMessageService;

    @Autowired
    private ChatConversationService chatConversationService;

    /**
     * 根据会话ID获取消息列表（仅限会话所属用户）
     */
    @GetMapping("/list")
    public R<List<ChatMessage>> list(@RequestParam String conversationId) {
        checkConversationOwner(conversationId);
        List<ChatMessage> messages = chatMessageService.listByConversationId(conversationId,20);
        return R.ok(messages);
    }

    /**
     * 根据消息唯一标识查询消息详情（仅限会话所属用户）
     */
    @GetMapping("/detail")
    public R<ChatMessage> detail(@RequestParam String messageId) {
        ChatMessage message = chatMessageService.getByMessageId(messageId);
        if (message == null) {
            return R.fail("消息不存在");
        }
        checkConversationOwner(message.getConversationId());
        return R.ok(message);
    }



    /**
     * 更新消息详情（改写内容、token统计等，仅限会话所属用户）
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody ChatMessage chatMessage) {
        if (chatMessage.getMessageId() == null) {
            return R.fail("消息ID不能为空");
        }
        ChatMessage existing = chatMessageService.getByMessageId(chatMessage.getMessageId());
        if (existing == null) {
            return R.fail("消息不存在");
        }
        checkConversationOwner(existing.getConversationId());
        chatMessageService.updateMessageDetail(
                chatMessage.getMessageId(),
                chatMessage.getTransformContent(),
                chatMessage.getTokenCount(),
                chatMessage.getModelName());
        return R.ok(null, "更新成功");
    }

    /**
     * 根据会话ID删除所有消息（仅限会话所属用户）
     */
    @DeleteMapping("/delete/{conversationId}")
    public R<Void> deleteByConversationId(@PathVariable String conversationId) {
        checkConversationOwner(conversationId);
        chatMessageService.deleteByConversationId(conversationId);
        return R.ok(null, "删除成功");
    }

    /**
     * 校验会话归属：会话必须存在且属于当前登录用户，否则抛业务异常
     */
    private void checkConversationOwner(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw new BusinessException("会话ID不能为空");
        }
        ChatConversation conversation = chatConversationService.getByConversationId(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        if (!Objects.equals(conversation.getUserId(), StpUtil.getLoginIdAsString())) {
            throw new BusinessException("无权访问该会话");
        }
    }

}
