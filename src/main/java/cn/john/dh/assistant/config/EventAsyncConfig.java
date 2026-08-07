package cn.john.dh.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步事件线程池配置
 * <p>
 * 开启 @EnableAsync 并提供文档事件监听使用的 eventListenerExecutor 线程池，
 * 使 @Async("eventListenerExecutor") + @TransactionalEventListener 组合生效，
 * 避免向量嵌入等耗时操作阻塞 HTTP 请求线程。
 * </p>
 *
 * @Author John
 * @Date 2026-08-06
 */
@Configuration
@EnableAsync
public class EventAsyncConfig {

    /**
     * 文档事件监听专用线程池
     *
     * @return 线程池执行器
     */
    @Bean("eventListenerExecutor")
    public Executor eventListenerExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(2);
        // 最大线程数
        executor.setMaxPoolSize(4);
        // 队列容量
        executor.setQueueCapacity(100);
        // 线程名前缀，方便日志排查
        executor.setThreadNamePrefix("doc-event-");
        // 队列满时由调用线程执行，避免任务丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
