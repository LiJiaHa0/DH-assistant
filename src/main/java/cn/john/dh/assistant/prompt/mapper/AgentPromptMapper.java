package cn.john.dh.assistant.prompt.mapper;

import cn.john.dh.assistant.prompt.domain.entity.AgentPrompt;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent Prompt配置 Mapper 接口
 *
 * @Author John
 * @Date 2026-07-21
 */
@Mapper
public interface AgentPromptMapper extends BaseMapper<AgentPrompt> {

}
