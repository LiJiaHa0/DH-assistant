package cn.john.dh.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * WebMvc 异步支持配置
 * <p>
 * 项目 SSE 流式响应（Controller 返回 Flux）运行在 Spring MVC 之上，
 * 未配置 AsyncTaskExecutor 时框架默认使用 SimpleAsyncTaskExecutor（每次请求新建线程，无复用、无上限），
 * 启动时会输出警告且不适合生产负载。
 * 此处提供专用线程池，消除警告并保证流式响应的异步处理线程可控。
 * </p>
 *
 * @Author John
 * @Date 2026-08-08
 */
@Configuration
public class WebMvcAsyncConfig implements WebMvcConfigurer {

    /**
     * 配置 MVC 异步处理：指定 SSE 流式响应使用的线程池
     *
     * @param configurer 异步支持配置器
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcAsyncExecutor());
        // SSE 长连接场景不设置超时，沿用容器默认行为，避免流式会话被提前中断
    }

    /**
     * MVC 异步处理专用线程池（SSE 流式响应）
     *
     * @return 线程池执行器
     */
    @Bean("mvcAsyncExecutor")
    public ThreadPoolTaskExecutor mvcAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数
        executor.setCorePoolSize(8);
        // 最大线程数
        executor.setMaxPoolSize(32);
        // 队列容量
        executor.setQueueCapacity(200);
        // 线程名前缀，方便日志排查
        executor.setThreadNamePrefix("mvc-async-");
        // 队列满时由调用线程执行，避免请求丢失
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
