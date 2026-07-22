package cn.john.dh.assistant.chat.mapper;

import cn.john.dh.assistant.chat.domain.entity.ChatConversation;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话会话 Mapper 接口
 *
 * @Author John
 * @Date 2026-07-19
 */
@Mapper
public interface ChatConversationMapper extends BaseMapper<ChatConversation> {

}
