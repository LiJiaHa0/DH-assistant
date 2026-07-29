package cn.john.dh.assistant.entity;

/**
 * @Author John
 * @Date 2026-07-27 14:44
 */
public record PlanTask(
        String id,           // 任务唯一标识
        String instruction,  // 任务执行指令
        int order            // 任务执行顺序
) {
}
