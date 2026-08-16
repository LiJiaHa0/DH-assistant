package cn.john.dh.assistant.chat.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import cn.john.dh.assistant.chat.service.ChatConversationService;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.common.R;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;

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
     * 根据会话唯一标识查询会话详情（仅限会话所属用户）
     */
    @GetMapping("/detail")
    public R<ChatConversation> detail(@RequestParam String conversationId) {
        ChatConversation conversation = requireOwnedConversation(conversationId);
        return R.ok(conversation);
    }

    /**
     * 更新会话信息（如重命名标题，仅限会话所属用户；禁止篡改 userId 归属）
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody ChatConversation conversation) {
        if (conversation.getConversationId() == null || conversation.getConversationId().isBlank()) {
            return R.fail("会话ID不能为空");
        }
        // 校验归属（同时防止修改他人会话）
        requireOwnedConversation(conversation.getConversationId());
        // 归属字段强制为当前用户，防止通过请求体篡改 userId
        conversation.setUserId(getCurrentUserId());
        // 仅允许更新标题等业务字段，主键/会话ID/归属由服务端控制
        chatConversationService.updateById(conversation);
        return R.ok(null, "更新成功");
    }


    /**
     * 删除会话（仅限会话所属用户）
     */
    @DeleteMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ChatConversation conversation = chatConversationService.getById(id);
        if (conversation == null) {
            return R.fail("会话不存在");
        }
        if (!Objects.equals(conversation.getUserId(), getCurrentUserId())) {
            throw new BusinessException("无权删除该会话");
        }
        chatConversationService.removeById(id);
        return R.ok(null, "删除成功");
    }

    /**
     * 查询会话并校验归属，返回会话实体
     */
    private ChatConversation requireOwnedConversation(String conversationId) {
        ChatConversation conversation = chatConversationService.getByConversationId(conversationId);
        if (conversation == null) {
            throw new BusinessException("会话不存在");
        }
        if (!Objects.equals(conversation.getUserId(), getCurrentUserId())) {
            throw new BusinessException("无权访问该会话");
        }
        return conversation;
    }

    /**
     * 从 Sa-Token 获取当前登录用户ID
     */
    private String getCurrentUserId() {
        return StpUtil.getLoginIdAsString();
    }
}
