package cn.john.dh.assistant.prompt.service.impl;

import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.BaseAgentPrompts;
import cn.john.dh.assistant.prompt.domain.entity.AgentPrompt;
import cn.john.dh.assistant.prompt.mapper.AgentPromptMapper;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Agent Prompt配置 Service 实现类
 *
 * @Author John
 * @Date 2026-07-21
 */
@Service
public class AgentPromptServiceImpl extends ServiceImpl<AgentPromptMapper, AgentPrompt> implements AgentPromptService {

    @Override
    public String getPromptContentAndBasePrompt(AgentType agentType, PromptKey promptKey) {
        AgentPrompt prompt = getPrompt(agentType, promptKey);
        return prompt != null ? BaseAgentPrompts.getBasePromptWithPrefix(prompt.getContent()) : null;
    }

    @Override
    public String getPromptContent(AgentType agentType, PromptKey promptKey) {
        AgentPrompt prompt = getPrompt(agentType, promptKey);
        return prompt != null ? prompt.getContent() : null;
    }

    @Override
    public AgentPrompt getPrompt(AgentType agentType, PromptKey promptKey) {
        return getOne(new LambdaQueryWrapper<AgentPrompt>()
                .eq(AgentPrompt::getAgentType, agentType.getCode())
                .eq(AgentPrompt::getPromptKey, promptKey.getCode())
                .eq(AgentPrompt::getStatus, 1)
                .orderByDesc(AgentPrompt::getVersion)
                .last("LIMIT 1"));
    }

    @Override
    public boolean updatePromptContent(AgentType agentType, PromptKey promptKey, String content) {
        AgentPrompt existing = getPrompt(agentType, promptKey);
        if (existing == null) {
            return false;
        }
        return this.update(new LambdaUpdateWrapper<AgentPrompt>()
                .eq(AgentPrompt::getAgentType, agentType.getCode())
                .eq(AgentPrompt::getPromptKey, promptKey.getCode())
                .eq(AgentPrompt::getId, existing.getId())
                .set(AgentPrompt::getContent, content)
                .set(AgentPrompt::getVersion, existing.getVersion() + 1)
                .set(AgentPrompt::getUpdatedAt, LocalDateTime.now()));
    }

}
