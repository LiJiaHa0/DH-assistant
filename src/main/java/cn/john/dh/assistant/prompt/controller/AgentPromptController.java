package cn.john.dh.assistant.prompt.controller;

import cn.dev33.satoken.stp.StpUtil;
import cn.john.dh.assistant.common.BusinessException;
import cn.john.dh.assistant.common.R;
import cn.john.dh.assistant.constant.AgentType;
import cn.john.dh.assistant.constant.PromptKey;
import cn.john.dh.assistant.prompt.domain.entity.AgentPrompt;
import cn.john.dh.assistant.prompt.service.AgentPromptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * Agent Prompt配置控制器
 * <p>
 * 读操作（列表/详情）登录即可；写操作（新增/修改/删除）仅限配置的管理员
 * （app.admin-user-ids，逗号分隔的用户ID列表）。防止任意用户篡改系统提示词。
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
     * 管理员用户ID列表（逗号分隔，对应 user_info.id；为空时写操作全部拒绝）
     */
    @Value("${app.admin-user-ids:}")
    private String adminUserIds;

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
     * 新增Prompt配置（仅管理员）
     */
    @PostMapping("/save")
    public R<Void> save(@RequestBody AgentPrompt agentPrompt) {
        checkAdmin();
        agentPromptService.save(agentPrompt);
        return R.ok(null, "保存成功");
    }

    /**
     * 更新Prompt配置（仅管理员）
     */
    @PutMapping("/update")
    public R<Void> update(@RequestBody AgentPrompt agentPrompt) {
        checkAdmin();
        agentPromptService.updateById(agentPrompt);
        return R.ok(null, "更新成功");
    }

    /**
     * 删除Prompt配置（仅管理员）
     */
    @DeleteMapping("/delete/{id}")
    public R<Void> delete(@PathVariable Long id) {
        checkAdmin();
        agentPromptService.removeById(id);
        return R.ok(null, "删除成功");
    }

    /**
     * 校验当前用户是否在管理员名单中
     */
    private void checkAdmin() {
        String current = StpUtil.getLoginIdAsString();
        List<String> admins = Arrays.stream(adminUserIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (!admins.contains(current)) {
            throw new BusinessException("仅管理员可执行该操作");
        }
    }

}
