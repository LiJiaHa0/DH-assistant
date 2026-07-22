package cn.john.dh.assistant.prompt.controller;

import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.domain.entity.AgentPrompt;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Agent Prompt配置控制器
 *
 * @Author John
 * @Date 2026-07-21
 */
@RestController
@RequestMapping("/agent/prompt")
public class AgentPromptController {

    @Autowired
    private AgentPromptService agentPromptService;

    /**
     * 获取所有Prompt配置列表
     */
    @GetMapping("/list")
    public R<List<AgentPrompt>> list() {
        return R.ok(agentPromptService.list());
    }

    /**
     * 根据Agent类型和PromptKey查询Prompt详情
     */
    @GetMapping("/detail")
    public R<AgentPrompt> detail(@RequestParam String agentType, @RequestParam String promptKey) {
        AgentType type = AgentType.valueOf(agentType);
        PromptKey key = PromptKey.valueOf(promptKey);
        AgentPrompt prompt = agentPromptService.getPrompt(type, key);
        if (prompt == null) {
            return R.fail("Prompt配置不存在");
        }
        return R.ok(prompt);
    }

    /**
     * 新增Prompt配置
     */
    @PostMapping("/save")
    public R<Void> save(@RequestBody AgentPrompt agentPrompt) {
        agentPromptService.save(agentPrompt);
        return R.ok(null, "保存成功");
    }

    /**
     * 更新Prompt配置
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody AgentPrompt agentPrompt) {
        agentPromptService.updateById(agentPrompt);
        return R.ok(null, "更新成功");
    }

    /**
     * 删除Prompt配置
     */
    @DeleteMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        agentPromptService.removeById(id);
        return R.ok(null, "删除成功");
    }

}
