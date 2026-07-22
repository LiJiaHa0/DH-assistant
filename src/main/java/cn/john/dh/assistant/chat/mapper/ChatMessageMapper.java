package cn.john.dh.assistant.chat.mapper;

import cn.john.dh.assistant.chat.domain.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI对话消息 Mapper 接口
 *
 * @Author John
 * @Date 2026-07-20
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}
