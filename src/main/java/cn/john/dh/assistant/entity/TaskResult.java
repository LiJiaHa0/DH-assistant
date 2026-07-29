package cn.john.dh.assistant.entity;

/**
 * @Author John
 * @Date 2026-07-27 15:18
 */
public record TaskResult(
        String taskId,     // 任务唯一标识
        boolean success,   // 任务是否执行成功
        String output,     // 任务执行输出内容
        String error       // 任务执行错误信息
) {

}
