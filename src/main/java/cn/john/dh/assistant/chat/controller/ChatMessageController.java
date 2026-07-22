package cn.john.dh.assistant.chat.controller;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import cn.john.dh.assistant.chat.service.ChatMessageService;
import cn.john.dh.assistant.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    /**
     * 根据会话ID获取消息列表
     */
    @GetMapping("/list")
    public R<List<ChatMessage>> list(@RequestParam String conversationId) {
        List<ChatMessage> messages = chatMessageService.listByConversationId(conversationId,20);
        return R.ok(messages);
    }

    /**
     * 根据消息唯一标识查询消息详情
     */
    @GetMapping("/detail")
    public R<ChatMessage> detail(@RequestParam String messageId) {
        ChatMessage message = chatMessageService.getByMessageId(messageId);
        if (message == null) {
            return R.fail("消息不存在");
        }
        return R.ok(message);
    }



    /**
     * 更新消息详情（改写内容、token统计等）
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody ChatMessage chatMessage) {
        chatMessageService.updateMessageDetail(
                chatMessage.getMessageId(),
                chatMessage.getTransformContent(),
                chatMessage.getTokenCount(),
                chatMessage.getModelName());
        return R.ok(null, "更新成功");
    }

    /**
     * 根据会话ID删除所有消息
     */
    @DeleteMapping("/delete/{conversationId}")
    public R<Void> deleteByConversationId(@PathVariable String conversationId) {
        chatMessageService.deleteByConversationId(conversationId);
        return R.ok(null, "删除成功");
    }

}
