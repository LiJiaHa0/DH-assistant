package cn.john.dh.assistant.prompt.domain.entity;

import cn.john.dh.assistant.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * Agent Prompt配置实体
 *
 * @Author John
 * @Date 2026-07-21
 */
@Data
@TableName("agent_prompt")
public class AgentPrompt {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Agent类型标识
     */
    @TableField("agent_type")
    private String agentType;

    /**
     * Prompt用途标识
     */
    @TableField("prompt_key")
    private String promptKey;

    /**
     * Prompt内容
     */
    @TableField("content")
    private String content;

    /**
     * 版本号
     */
    @TableField("version")
    private Integer version;

    /**
     * 状态：0-禁用 1-启用
     */
    @TableField("status")
    private Integer status;

    /**
     * 说明
     */
    @TableField("description")
    private String description;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    protected LocalDateTime createdAt;

    /**
     * 修改时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    protected LocalDateTime updatedAt;


    /**
     * 是否删除：0-未删除，1-已删除
     */
    @TableLogic
    protected Integer deleted;

}
