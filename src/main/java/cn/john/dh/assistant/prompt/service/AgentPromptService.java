package cn.john.dh.assistant.prompt.service;

import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.domain.entity.AgentPrompt;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * Agent Prompt配置 Service 接口
 *
 * @Author John
 * @Date 2026-07-21
 */
public interface AgentPromptService extends IService<AgentPrompt> {

    /**
     * 根据Agent类型和PromptKey获取当前生效的Prompt内容
     *
     * @param agentType Agent类型
     * @param promptKey Prompt用途标识
     * @return Prompt内容，不存在则返回 null
     */
    String getPromptContentAndBasePrompt(AgentType agentType, PromptKey promptKey);

    /**
     * 根据Agent类型和PromptKey获取当前生效的Prompt内容
     *
     * @param agentType Agent类型
     * @param promptKey Prompt用途标识
     * @return Prompt内容，不存在则返回 null
     */
    String getPromptContent(AgentType agentType, PromptKey promptKey);

    /**
     * 根据Agent类型和PromptKey查询Prompt配置（含版本、状态等完整信息）
     *
     * @param agentType Agent类型
     * @param promptKey Prompt用途标识
     * @return Prompt配置实体，不存在则返回 null
     */
    AgentPrompt getPrompt(AgentType agentType, PromptKey promptKey);

    /**
     * 根据Agent类型和PromptKey更新Prompt内容（版本号自增）
     *
     * @param agentType Agent类型
     * @param promptKey Prompt用途标识
     * @param content   新Prompt内容
     * @return 是否成功
     */
    boolean updatePromptContent(AgentType agentType, PromptKey promptKey, String content);

}
