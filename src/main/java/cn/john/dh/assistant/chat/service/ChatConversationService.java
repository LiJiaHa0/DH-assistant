package cn.john.dh.assistant.chat.service;

import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * AI对话会话 Service 接口
 *
 * @Author John
 * @Date 2026-07-19
 */
public interface ChatConversationService extends IService<ChatConversation> {

    /**
     * 根据用户ID查询会话列表
     *
     * @param userId 用户ID
     * @return 会话列表
     */
    List<ChatConversation> listByUserId(String userId);

    /**
     * 根据会话唯一标识查询会话
     *
     * @param conversationId 会话唯一标识
     * @return 会话信息，不存在则返回 null
     */
    ChatConversation getByConversationId(String conversationId);

    /**
     * 创建新会话
     *
     * @param userId 用户ID
     * @param title  会话标题
     * @return 会话ID
     */
    String createConversation(String userId, String title);

    /**
     * 更新会话标题
     *
     * @param conversationId 会话ID
     * @param title          新标题
     * @return 是否成功
     */
    boolean updateTitle(String conversationId, String title);

    /**
     * 归档会话
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean archiveConversation(String conversationId);

    /**
     * 删除会话
     *
     * @param conversationId 会话ID
     * @return 是否成功
     */
    boolean deleteConversation(String conversationId);

}
