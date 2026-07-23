package cn.john.dh.assistant.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.constant.ChatConversationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * AI对话会话控制器
 *
 * @Author John
 * @Date 2026-07-19
 */
@RestController
@RequestMapping("/chat/conversation")
public class ChatConversationController {

    @Autowired
    private ChatConversationService chatConversationService;

    /**
     * 获取当前用户的会话列表
     */
    @GetMapping("/list")
    public R<List<ChatConversation>> list() {
        String userId = getCurrentUserId();
        List<ChatConversation> conversations = chatConversationService.listByUserId(userId);
        return R.ok(conversations);
    }


    /**
     * 根据会话唯一标识查询会话详情
     */
    @GetMapping("/detail")
    public R<ChatConversation> detail(@RequestParam String conversationId) {
        ChatConversation conversation = chatConversationService.getByConversationId(conversationId);
        if (conversation == null) {
            return R.fail("会话不存在");
        }
        return R.ok(conversation);
    }

    /**
     * 更新会话信息（如重命名标题）
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody ChatConversation conversation) {
        chatConversationService.updateById(conversation);
        return R.ok(null, "更新成功");
    }


    /**
     * 删除会话
     */
    @DeleteMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        chatConversationService.removeById(id);
        return R.ok(null, "删除成功");
    }

    /**
     * 从 Sa-Token 获取当前登录用户ID
     */
    private String getCurrentUserId() {
        return StpUtil.getLoginIdAsString();
    }
}
