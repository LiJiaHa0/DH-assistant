package cn.john.dh.assistant.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ollama 聊天模型手动配置
 * <p>
 * 主聊天模型切换为百炼 Token Plan 后，application.yml 中设置了 spring.ai.model.chat=openai，
 * 该属性会同时禁用 OllamaChatAutoConfiguration（其条件注解要求 havingValue=ollama），
 * 导致 OllamaChatModel Bean 不再自动注册。
 * 此处手动构建 OllamaChatModel，继续为会话标题生成、图片描述等轻量场景提供本地模型支持。
 *
 * @Author John
 */
@Configuration
public class OllamaChatModelConfig {

    /**
     * 构建本地 Ollama 聊天模型
     *
     * @param baseUrl     Ollama 服务地址
     * @param model       模型名称
     * @param temperature 温度参数
     * @return OllamaChatModel 实例
     */
    @Bean
    public OllamaChatModel ollamaChatModel(
            @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${spring.ai.ollama.chat.model:qwen3-vl:4b}") String model,
            @Value("${spring.ai.ollama.chat.options.temperature:0.3}") Double temperature) {
        // 构建 Ollama API 客户端
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .build();
        // 构建聊天模型，沿用 yml 中的模型与温度配置
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .defaultOptions(OllamaChatOptions.builder()
                        .model(model)
                        .temperature(temperature)
                        .build())
                .build();
    }
}
