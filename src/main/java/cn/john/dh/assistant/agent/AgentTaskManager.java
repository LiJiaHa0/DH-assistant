package cn.john.dh.assistant.agent;

import okio.Sink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @Author John
 * @Date 2026-07-20 10:21
 */
@Component
public class AgentTaskManager {

    // 日志记录器实例
    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    // 使用ConcurrentHashMap存储运行中的任务，保证线程安全
    private final Map<String, TaskInfo> runningTasks = new ConcurrentHashMap<>();

    /**
     * 检查是否存在正在运行的任务
     * @param conversationId
     * @return
     */
    public boolean hasRunningTask(String conversationId) {
        return runningTasks.containsKey(conversationId);
    }

    /**
     * 注册一个任务
     * @param conversationId
     * @param sink
     * @param createTime
     * @param agentType
     * @param disposable
     * @return
     */
    public TaskInfo registerTask(String conversationId, Sinks.Many<String> sink, Integer createTime, String agentType, Disposable disposable) {
        //检查是否存在正在运行的任务
        if(hasRunningTask(conversationId)){
            //存在正在运行的任务，返回null
            return null;
        }
        TaskInfo taskInfo = new TaskInfo(sink, null, Instant.now(), agentType);
        // 将新任务信息存入Map
        runningTasks.put(conversationId, taskInfo);
        // 记录任务注册日志
        log.info("Registered task for conversation: {}, agent: {}", conversationId, agentType);
        return taskInfo;
    }

    /**
     * 为会话注册新的任务
     * 如果该会话已有运行中的任务则返回null
     *
     * @param conversationId 会话ID
     * @param sink           响应式信号发射器
     * @param agentType      代理类型标识
     * @return 新注册的任务信息，已存在运行中任务时返回null
     */
    public TaskInfo registerTask(String conversationId, Sinks.Many<String> sink, String agentType) {
        // 检查是否已有运行中的任务
        if (hasRunningTask(conversationId)) {
            // 如果有则返回null
            return null;
        }
        // 创建新的任务信息对象
        TaskInfo taskInfo = new TaskInfo(sink, null, Instant.now(), agentType);
        // 将新任务信息存入Map
        runningTasks.put(conversationId, taskInfo);
        // 记录任务注册日志
        log.info("Registered task for conversation: {}, agent: {}", conversationId, agentType);
        return taskInfo;
    }

    /**
     * 设置任务的订阅引用，用于释放订阅资源
     * @param conversationId
     * @param disposable
     */
    public void setDisposable(String conversationId, Disposable disposable) {
        TaskInfo taskInfo = runningTasks.get(conversationId);
        if (taskInfo != null) {
            taskInfo.disposable = disposable;
        }
        log.info("Set disposable for conversation: {}", conversationId);
    }

    /**
     * 停止指定会话的运行中任务
     * @param conversationId
     * @return
     */
    public boolean stopTask(String conversationId){
        // 移除任务信息
        TaskInfo info = runningTasks.remove(conversationId);
        if(info == null){
            return false;
        }
        // 判断订阅是否存在且未释放
        if (info.disposable != null && !info.disposable.isDisposed()) {
            // 释放订阅资源
            info.disposable.dispose();
        }
        // 完成sink信号发射
        info.sink.tryEmitComplete();
        // 记录任务停止日志
        log.info("Stopped task for conversation: {}", conversationId);
        // 返回停止成功
        return true;
    }
    /**
     * 任务信息类
     */
    public static class TaskInfo {

        /**
         * 响应式信号发射器，用于推送任务结果
         */
        final Sinks.Many<String> sink;
        /**
         * 订阅引用，用于释放订阅资源
         */
        volatile Disposable disposable;
        /**
         * 任务创建时间
         */
        final Instant createTime;
        /**
         * 代理类型标识
         */
        final String agentType;
        /**
         * 任务完成标记，使用volatile保证可见性
         */
        volatile boolean completed = false;


        public TaskInfo(Sinks.Many<String> sink, Disposable disposable, Instant createTime,
                        String agentType) {
            this.sink = sink;
            this.createTime = createTime;
            this.agentType = agentType;
            this.disposable = disposable;
        }

        /**
         * 判断任务是否已完成
         * 完成标记为true或订阅已释放均视为完成
         *
         * @return 任务是否已完成
         */
        boolean isCompleted() {
            // 完成标记为true或订阅已释放则视为完成
            return completed || (disposable != null && disposable.isDisposed());
        }
    }
}
